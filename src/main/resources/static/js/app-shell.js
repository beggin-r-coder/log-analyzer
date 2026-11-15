/* Log Analyzer desktop app: turns the dashboard into an application window — sidebar navigation, one screen
 * at a time, a toolbar per screen, a status bar, settings, and app-style keyboard shortcuts. */
(function () {
	'use strict';
	const LA = window.LA;
	if (!LA || !document.body.classList.contains('app')) return;
	const $ = (id) => document.getElementById(id);
	const esc = LA.esc;

	const I = {
		overview: '<path d="M4 13h6V4H4zM14 20h6v-9h-6zM14 4v4h6V4zM4 20h6v-4H4z"/>',
		logs: '<path d="M4 6h16M4 12h16M4 18h10"/><circle cx="19" cy="18" r="2"/>',
		live: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
		incidents: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
		analysis: '<path d="M12 3l1.8 4.6L18.5 9l-4.7 1.4L12 15l-1.8-4.6L5.5 9l4.7-1.4z"/><path d="M19 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>',
		sources: '<ellipse cx="12" cy="5.5" rx="8" ry="2.5"/><path d="M4 5.5v13c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-13M4 12c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5"/>',
		settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
		power: '<path d="M12 3v9"/><path d="M6.3 6.3a8 8 0 1 0 11.4 0"/>',
		refresh: '<path d="M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4"/>',
		collapse: '<path d="M15 6l-6 6 6 6"/>'
	};
	const svg = (k, s) => `<svg width="${s || 18}" height="${s || 18}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${I[k]}</svg>`;

	const VIEWS = [
		{ id: 'overview', label: 'Overview', sub: 'Health, volume and errors across all your log sources', widgets: ['kpi-total', 'kpi-info', 'kpi-warn', 'kpi-error', 'kpi-throughput', 'kpi-fatal', 'kpi-services', 'kpi-incidents', 'impact', 'sla', 'health', 'trend', 'donut', 'categories', 'volume', 'formats', 'errorrate', 'svcerrors', 'services'], tools: ['range', 'customize', 'export'] },
		{ id: 'logs', label: 'Logs', sub: 'Search, filter and sort every event that was read', widgets: ['explorer'], tools: ['range', 'export'] },
		{ id: 'live', label: 'Live', sub: 'New events as they are read from your sources', widgets: ['livefeed'], tools: [] },
		{ id: 'incidents', label: 'Incidents', sub: 'Opened automatically by alert rules: acknowledge, add notes, resolve', widgets: ['incidentdesk'], tools: ['range', 'export'] },
		{ id: 'analysis', label: 'Analysis', sub: 'Error patterns, probable root causes and recovery steps', widgets: ['aianalysis', 'incidents'], tools: ['range', 'export'] },
		{ id: 'sources', label: 'Sources', sub: 'Siebel log folders and databases this app reads from', widgets: [], tools: [] },
		{ id: 'settings', label: 'Settings', sub: 'Appearance, alerts, storage and version', widgets: [], tools: [] }
	];
	const viewOf = (widget) => (VIEWS.find((v) => v.widgets.includes(widget)) || VIEWS[0]).id;
	let current = null;

	// ------------------------------------------------------------------ build the window
	const shell = document.createElement('div');
	shell.className = 'app-shell';
	shell.innerHTML = `
		<aside class="app-side">
			<div class="app-brand">
				<span class="app-logo"><svg viewBox="0 0 22 22"><rect width="22" height="22" rx="6"/><path d="M5 12.5h2.6l2-5 2.8 8 2-4.5H17" fill="none" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
				<div class="app-brand-text"><b>Log Analyzer</b><small>${esc((window.APP_BRAND && window.APP_BRAND.name) || '')}</small></div>
			</div>
			<nav class="app-nav" aria-label="Screens">
				${VIEWS.filter((v) => v.id !== 'settings').map((v, i) => `<button class="app-nav-item" data-view="${v.id}" title="${v.label} (Ctrl+${i + 1})">${svg(v.id)}<span>${v.label}</span>${v.id === 'incidents' ? '<em class="app-badge" id="appIncBadge" hidden>0</em>' : ''}${v.id === 'sources' ? '<em class="app-dot" id="appSrcDot" hidden></em>' : ''}</button>`).join('')}
			</nav>
			<div class="app-side-bottom">
				<button class="app-nav-item" data-view="settings" title="Settings (Ctrl+,)">${svg('settings')}<span>Settings</span></button>
				<button class="app-nav-item quiet" id="appQuit" title="Quit Log Analyzer">${svg('power')}<span>Quit</span></button>
			</div>
		</aside>
		<section class="app-main">
			<header class="app-toolbar">
				<div class="app-title"><h1 id="appTitle">Overview</h1><p id="appSub"></p></div>
				<div class="app-tools" id="appTools">
					<button class="btn ghost icon" id="appRefresh" title="Refresh (F5)">${svg('refresh', 16)}</button>
				</div>
			</header>
			<div class="app-views" id="appViews">
				${VIEWS.map((v) => `<div class="app-view" data-view="${v.id}" hidden>${v.id === 'sources' || v.id === 'settings' ? '' : '<div class="grid"></div>'}</div>`).join('')}
			</div>
		</section>
		<div class="app-status" id="appStatus" role="status">
			<span class="st-live" id="stLive"></span>
			<button class="st-item" id="stSources" title="Log sources"></button>
			<span class="st-item" id="stRate"></span>
			<span class="st-spacer"></span>
			<button class="st-item" id="stInc" title="Open incidents"></button>
			<span class="st-item" id="stVer"></span>
		</div>`;
	document.body.prepend(shell);
	const viewEl = (id) => shell.querySelector(`.app-view[data-view="${id}"]`);

	// move the existing widgets into their screens (event handlers stay attached)
	VIEWS.forEach((v) => {
		const grid = viewEl(v.id).querySelector('.grid');
		v.widgets.forEach((w) => { const el = document.querySelector(`[data-widget="${w}"]`); if (el) grid.appendChild(el); });
	});
	if (window.LA && window.LA.applyLayout) window.LA.applyLayout(); // the user's own order and sizes within each screen
	const src = $('sourcesView');
	if (src) viewEl('sources').appendChild(src);
	buildSettings(viewEl('settings'));

	// toolbar & status bar reuse the existing controls
	const tools = $('appTools');
	const range = $('rangeBtn') && $('rangeBtn').closest('.menu-wrap');
	if (range) { range.dataset.tool = 'range'; tools.prepend(range); }
	if ($('customizeBtn')) { $('customizeBtn').dataset.tool = 'customize'; tools.appendChild($('customizeBtn')); }
	const theme = document.querySelector('.topbar [data-theme-toggle]');
	if (theme) tools.appendChild(theme);
	if ($('pdfBtn')) { $('pdfBtn').dataset.tool = 'export'; tools.appendChild($('pdfBtn')); }
	if ($('livePill')) $('stLive').appendChild($('livePill'));
	document.body.classList.add('app-ready');

	// ------------------------------------------------------------------ navigation
	function go(id, opts) {
		const v = VIEWS.find((x) => x.id === id) || VIEWS[0];
		const focusSearch = () => { if (v.id === 'logs' && opts && opts.focusSearch && $('logSearch')) setTimeout(() => { $('logSearch').focus(); $('logSearch').select(); }, 50); };
		if (current === v.id && !(opts && opts.force)) { focusSearch(); return; }
		current = v.id;
		shell.querySelectorAll('.app-view').forEach((el) => { el.hidden = el.dataset.view !== v.id; });
		shell.querySelectorAll('.app-nav-item[data-view]').forEach((b) => b.classList.toggle('active', b.dataset.view === v.id));
		$('appTitle').textContent = v.label;
		$('appSub').textContent = v.sub;
		tools.querySelectorAll('[data-tool]').forEach((t) => { t.hidden = !v.tools.includes(t.dataset.tool); });
		const view = viewEl(v.id);
		view.classList.remove('enter'); void view.offsetWidth; view.classList.add('enter');
		view.scrollTop = 0;
		history.replaceState(null, '', location.pathname + '#' + v.id); // no history entries: the window can still close itself
		requestAnimationFrame(() => view.querySelectorAll('canvas').forEach((cv) => { const c = window.Chart && Chart.getChart(cv); if (c) c.resize(); }));
		focusSearch();
	}
	shell.addEventListener('click', (ev) => {
		const b = ev.target.closest('.app-nav-item[data-view]');
		if (b) go(b.dataset.view);
	});
	$('appQuit').addEventListener('click', () => LA.quitApp());
	$('appRefresh').addEventListener('click', refresh);
	$('stSources').addEventListener('click', () => go('sources'));
	$('stInc').addEventListener('click', () => go('incidents'));

	function refresh() {
		if (LA.refreshAll) LA.refreshAll();
		document.dispatchEvent(new CustomEvent('la:range'));
		const b = $('appRefresh');
		b.classList.remove('spin'); void b.offsetWidth; b.classList.add('spin');
	}

	window.LA_SHELL = { go, reveal: (widget) => go(viewOf(widget)), current: () => current };

	// ------------------------------------------------------------------ status bar
	async function status() {
		try {
			const s = await (await fetch('/api/sources/summary')).json();
			const prob = s.errors > 0;
			$('stSources').innerHTML = `<i class="st-dot ${s.enabled ? (prob ? 'warn' : 'ok') : 'off'}"></i>${s.enabled ? `Reading ${s.enabled} source${s.enabled === 1 ? '' : 's'}` : 'No sources connected'}${prob ? ` · ${s.errors} with a problem` : ''}`;
			$('appSrcDot').hidden = !prob;
			if (status.first === undefined) {
				status.first = false;
				const asked = new URLSearchParams(location.search).get('view') || location.hash.slice(1);
				go(s.sources === 0 ? 'sources' : VIEWS.some((v) => v.id === asked) ? asked : 'overview', { force: true });
			}
		} catch (e) { /* the offline screen explains */ }
		const sum = LA.summary;
		if (sum && sum.trend && sum.trend.length) {
			const mins = Math.max(1, (sum.to - sum.from) / 60000);
			$('stRate').textContent = `${(sum.totals.total / mins).toFixed(1)} events/min · ${LA.nf.format(sum.totals.total)} in range`;
		}
	}
	function incidents() {
		const n = Number(($('incNavBadge') || {}).textContent || 0), badge = $('appIncBadge');
		const hot = $('incNavBadge') && $('incNavBadge').classList.contains('hot');
		badge.hidden = !n;
		badge.textContent = n;
		badge.classList.toggle('hot', hot);
		$('stInc').innerHTML = `<i class="st-dot ${n ? (hot ? 'bad' : 'warn') : 'ok'}"></i>${n ? `${n} active incident${n === 1 ? '' : 's'}` : 'No active incidents'}`;
	}
	if ($('incNavBadge')) new MutationObserver(incidents).observe($('incNavBadge'), { childList: true, attributes: true, characterData: true, subtree: true });
	incidents();
	status();
	setInterval(() => { if (!document.hidden) status(); }, 5000);
	fetch('/api/desktop/info').then((r) => r.json()).then((i) => { $('stVer').textContent = 'v' + i.version; fillAbout(i); }).catch(() => {});

	// ------------------------------------------------------------------ settings
	function buildSettings(el) {
		el.innerHTML = `
			<div class="set-grid">
				<section class="card set-card">
					<h3>Appearance</h3><p>Colour theme of the app window.</p>
					<div class="seg set-theme" id="setTheme"><button data-m="light">Light</button><button data-m="dark">Dark</button><button data-m="system">Match Windows</button></div>
				</section>
				<section class="card set-card">
					<h3>Alert rules</h3><p>Error-rate and error-count rules that open incidents and route them to the owning team.</p>
					<button class="btn" id="setRules">Edit alert rules</button>
				</section>
				<section class="card set-card">
					<h3>Log sources</h3><p>Siebel log folders, network shares and databases to read from.</p>
					<button class="btn" id="setSources">Manage sources</button>
				</section>
				<section class="card set-card wide">
					<h3>Data &amp; storage</h3><p>Everything stays on this computer. Logs older than the retention period are removed automatically.</p>
					<dl class="set-dl">
						<dt>Data folder</dt><dd><code id="setData">—</code><button class="btn small" data-open="data">Open</button></dd>
						<dt>App log file</dt><dd><code id="setLog">—</code><button class="btn small" data-open="logs">Open folder</button></dd>
						<dt>Keeps logs for</dt><dd id="setRetention">—</dd>
					</dl>
				</section>
				<section class="card set-card wide">
					<h3>About</h3>
					<dl class="set-dl">
						<dt>Version</dt><dd id="setVer">—</dd>
						<dt>Runs on</dt><dd id="setRuntime">—</dd>
						<dt>Privacy</dt><dd>The app only answers on this computer (127.0.0.1); sources are read, never changed.</dd>
					</dl>
				</section>
			</div>`;
		const sync = () => el.querySelectorAll('#setTheme button').forEach((b) => b.classList.toggle('on', b.dataset.m === window.LA_THEME.mode()));
		el.querySelector('#setTheme').addEventListener('click', (e) => { const b = e.target.closest('[data-m]'); if (b) { window.LA_THEME.setMode(b.dataset.m); sync(); } });
		document.addEventListener('la:theme', sync);
		sync();
		el.querySelector('#setRules').addEventListener('click', () => $('incRulesBtn') && $('incRulesBtn').click());
		el.querySelector('#setSources').addEventListener('click', () => go('sources'));
		el.querySelectorAll('[data-open]').forEach((b) => b.addEventListener('click', async () => {
			try {
				const r = await fetch('/api/desktop/open-folder?which=' + b.dataset.open, { method: 'POST' });
				if (!r.ok) throw new Error((await r.json()).error);
			} catch (e) { window.LA_UI.alert({ title: 'Could not open the folder', message: e.message || 'Not available on this computer.' }); }
		}));
	}
	function fillAbout(i) {
		$('setData').textContent = i.dataDir;
		$('setLog').textContent = i.logFile;
		$('setVer').textContent = 'Log Analyzer ' + i.version;
		$('setRuntime').textContent = `${i.os} · Java ${i.java}`;
		$('setRetention').textContent = i.retentionDays ? `${i.retentionDays} days` : '—';
	}

	// ------------------------------------------------------------------ app behaviour
	document.addEventListener('keydown', (ev) => {
		const mod = ev.ctrlKey || ev.metaKey;
		if (ev.key === 'F5' || (mod && ev.key.toLowerCase() === 'r')) { ev.preventDefault(); refresh(); return; }
		if (mod && /^[1-6]$/.test(ev.key)) { ev.preventDefault(); go(VIEWS[Number(ev.key) - 1].id); return; }
		if (mod && ev.key === ',') { ev.preventDefault(); go('settings'); return; }
		if (mod && ev.key.toLowerCase() === 'f') { ev.preventDefault(); go('logs', { focusSearch: true }); return; }
		if (mod && ev.key.toLowerCase() === 'p') { ev.preventDefault(); if ($('pdfBtn')) $('pdfBtn').click(); }
	});
	// no browser context menu or link dragging on the window chrome
	shell.addEventListener('contextmenu', (ev) => { if (ev.target.closest('.app-side, .app-toolbar, .app-status')) ev.preventDefault(); });
	document.addEventListener('dragstart', (ev) => { if (ev.target.closest && ev.target.closest('a, img, svg')) ev.preventDefault(); });
})();
