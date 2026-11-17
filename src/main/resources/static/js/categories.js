/* Custom categories: the user's own groups of events (e.g. messages containing "Record not found"), matched by
 * text, codes or patterns, shown as KPI cards, charts over time and a comparison chart, with alerts. */
(function () {
	'use strict';
	const LA = window.LA;
	if (!LA) return;
	const $ = (id) => document.getElementById(id);
	const esc = LA.esc, nf = LA.nf;
	const ADMIN = !window.APP_USER || window.APP_USER.admin !== false;
	const PALETTE = ['#6366f1', '#e11d48', '#0ea5e9', '#f59e0b', '#10b981', '#8b5cf6', '#ec4899', '#14b8a6'];
	const FIELDS = { message: 'Message', any: 'Anywhere', service: 'Component / service', level: 'Level', errorCategory: 'Error class', traceId: 'Trace / SARM id', host: 'Host', logger: 'Logger' };
	const OPS = { contains: 'contains', notContains: 'does not contain', equals: 'is exactly', startsWith: 'starts with', endsWith: 'ends with', wildcard: 'matches  * ?', regex: 'matches pattern (regex)' };
	const OPS_TEXT = Object.assign({}, OPS, { wildcard: 'matches', regex: 'matches pattern' });
	const LEVELS = ['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG'];
	const CHARTS = { LINE: 'Line', AREA: 'Area', BAR: 'Bars', NONE: 'No chart' };
	const st = { cats: [], times: [], bucket: 60, editing: null, draft: null, previewChart: null, compareChart: null, charts: {}, loading: false, last: 0 };

	window.LA_CATS = { list: () => st.cats, refresh: () => load(true), newFromText: (t) => openEditor(null, t) };

	// ---------------------------------------------------------------- data
	async function load(force) {
		if (st.loading || (!force && Date.now() - st.last < 8000)) return;
		st.loading = true;
		try {
			const r = await LA.api(`/api/categories/stats?range=${encodeURIComponent(LA.range)}&tz=${LA.TZ_OFFSET}`);
			st.cats = r.categories;
			st.times = r.times;
			st.bucket = r.bucketMinutes;
			st.last = Date.now();
			renderManager();
			renderCompare();
			renderWidgets();
			const sel = $('categoryFilter');
			if (sel) LA.addCustomOptions(sel);
		} catch (e) { /* retried on the next refresh */ } finally { st.loading = false; }
	}
	document.addEventListener('la:refreshed', () => load(false));
	document.addEventListener('la:range', () => load(true));

	// ---------------------------------------------------------------- helpers
	const labels = () => st.times.map((t) => LA.bucketLabel(t, st.bucket));
	const alpha = (hex, a) => hex + Math.round(a * 255).toString(16).padStart(2, '0');
	function change(c) {
		if (c.previous == null) return '';
		if (!c.previous) return c.count ? '<span class="cat-delta up">new</span>' : '';
		const p = Math.round((c.count - c.previous) * 100 / c.previous);
		return `<span class="cat-delta ${p > 0 ? 'up' : p < 0 ? 'down' : ''}" title="vs the previous period (${nf.format(c.previous)})">${p > 0 ? '▲' : p < 0 ? '▼' : '='} ${Math.abs(p)}%</span>`;
	}
	function spark(series, color, w, h) {
		if (!series || !series.length) return '';
		const max = Math.max(1, ...series), step = w / Math.max(1, series.length - 1);
		const pts = series.map((v, i) => `${(i * step).toFixed(1)},${(h - 2 - (v / max) * (h - 4)).toFixed(1)}`).join(' ');
		return `<svg class="cat-spark" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true"><polyline points="0,${h} ${pts} ${w},${h}" fill="${alpha(color, 0.12)}" stroke="none"/><polyline points="${pts}" fill="none" stroke="${color}" stroke-width="1.6" stroke-linejoin="round"/></svg>`;
	}
	function describe(c) {
		const rules = (c.rules || []).map((r) => `${FIELDS[r.field || 'message'] || r.field} ${OPS_TEXT[r.op || 'contains'] || r.op} <q>${esc(r.value)}</q>${r.caseSensitive ? ' <small>(exact case)</small>' : ''}`);
		const joined = rules.length > 1 ? rules.join(c.matchAll ? ' <b>and</b> ' : ' <b>or</b> ') : rules.join('');
		return joined + (c.levels && c.levels.length ? ` · only ${c.levels.join(', ')}` : '');
	}
	const ago = (ms) => (ms ? LA.agoShort ? LA.agoShort(ms) : new Date(ms).toLocaleString() : 'not seen');
	function showLogs(id) { LA.filterExplorer({ custom: id }); }

	function chartOptions(type, color, onClick) {
		const C = LA.C || {};
		return {
			responsive: true, maintainAspectRatio: false, animation: { duration: 400 },
			interaction: { mode: 'index', intersect: false },
			onClick,
			plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => ` ${c.dataset.label}: ${nf.format(c.raw)}` } } },
			scales: {
				x: { grid: { display: false }, border: { display: false }, ticks: { color: C.faint, maxRotation: 0, autoSkip: true, autoSkipPadding: 18 } },
				y: { beginAtZero: true, grid: { color: C.grid, drawTicks: false }, border: { display: false }, ticks: { color: C.faint, padding: 8, precision: 0 } }
			}
		};
	}
	function dataset(c, type) {
		const color = c.color || PALETTE[0];
		return type === 'BAR'
			? { label: c.name, data: c.series, backgroundColor: alpha(color, 0.75), hoverBackgroundColor: color, borderRadius: 4, maxBarThickness: 28 }
			: { label: c.name, data: c.series, borderColor: color, backgroundColor: alpha(color, type === 'AREA' ? 0.18 : 0.06), fill: type === 'AREA', tension: 0.35, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4 };
	}
	function upsertChart(key, canvas, type, datasets, onClick) {
		const chartType = type === 'BAR' ? 'bar' : 'line';
		let ch = st.charts[key];
		if (ch && (ch.canvas !== canvas || ch.config.type !== chartType)) { ch.destroy(); ch = null; }
		if (!ch) {
			ch = st.charts[key] = new Chart(canvas, { type: chartType, data: { labels: labels(), datasets }, options: chartOptions(type, null, onClick) });
		} else {
			ch.data.labels = labels();
			ch.data.datasets = datasets;
			ch.update('none');
		}
		return ch;
	}

	// ---------------------------------------------------------------- manager list
	function renderManager() {
		const list = $('catList');
		if (!list) return;
		if (!st.cats.length) {
			list.innerHTML = `<div class="cat-empty big">
				<b>No categories yet</b>
				<span>A category groups events by your own rules, for example every message containing <q>Record not found</q>, every <q>SBL-DAT-*</q> code, or errors from one component. Each one can have a KPI card, a chart over time and an alert.</span>
				${ADMIN ? '<div class="cat-quick">Start from: <button class="chip" data-quick="Record not found">Record not found</button><button class="chip" data-quick="SBL-DAT-*">SBL-DAT-* codes</button><button class="chip" data-quick="timed out">Timeouts</button><button class="chip" data-quick="ORA-*">Oracle ORA- errors</button></div>' : ''}
			</div>`;
			return;
		}
		list.innerHTML = st.cats.map((c) => `
			<div class="cat-card${c.enabled ? '' : ' off'}" data-id="${c.id}" style="--cc:${esc(c.color)}">
				<div class="cat-card-main">
					<div class="cat-card-top"><i class="cat-dot"></i><b>${esc(c.name)}</b>
						${c.showKpi ? '<span class="cat-tag">KPI</span>' : ''}${c.chartType !== 'NONE' ? `<span class="cat-tag">${CHARTS[c.chartType]} chart</span>` : ''}
						${c.alertEnabled ? `<span class="cat-tag alert" title="Opens an incident at ${c.alertThreshold} events in ${c.alertWindowMinutes} min">⚑ ≥ ${c.alertThreshold} in ${c.alertWindowMinutes}m</span>` : ''}
						${c.enabled ? '' : '<span class="cat-tag">Paused</span>'}</div>
					<div class="cat-rules">${describe(c)}</div>
					${c.lastMessage ? `<div class="cat-last" title="${esc(c.lastMessage)}">Latest: ${esc(c.lastMessage)}</div>` : ''}
				</div>
				<div class="cat-card-stat">
					<div class="cat-count">${nf.format(c.count)} ${change(c)}</div>
					<div class="cat-sub">in ${esc(LA.RANGE_LABEL[LA.range] || LA.range)} · last ${c.lastSeen ? esc(ago(c.lastSeen)) : 'never'}</div>
					${spark(c.series, c.color, 150, 30)}
				</div>
				<div class="cat-card-btns">
					<button class="btn small" data-act="logs">View logs</button>
					${ADMIN ? `<button class="btn small" data-act="edit">Edit</button><button class="btn ghost small" data-act="toggle">${c.enabled ? 'Pause' : 'Resume'}</button><button class="btn ghost small" data-act="delete">Delete</button>` : ''}
				</div>
			</div>`).join('');
	}

	document.addEventListener('click', async (ev) => {
		const nw = ev.target.closest('[data-cat-new]');
		if (nw) { openEditor(null); return; }
		const quick = ev.target.closest('[data-quick]');
		if (quick) { openEditor(null, quick.dataset.quick); return; }
		const track = ev.target.closest('[data-track]');
		if (track) { openEditor(null, track.dataset.track); return; }
		const b = ev.target.closest('#catList [data-act]');
		if (!b) return;
		const c = st.cats.find((x) => x.id === Number(b.closest('[data-id]').dataset.id));
		if (!c) return;
		try {
			if (b.dataset.act === 'logs') showLogs(c.id);
			if (b.dataset.act === 'edit') openEditor(c);
			if (b.dataset.act === 'toggle') { await save(Object.assign({}, c, { enabled: !c.enabled }), c.id, true); }
			if (b.dataset.act === 'delete') {
				const ok = await LA_UI.confirm({ title: 'Delete this category?', message: `“${c.name}” and its KPI card and chart will be removed. The logs themselves stay.`, confirm: 'Delete', danger: true, icon: 'trash' });
				if (!ok) return;
				await fetch('/api/categories/' + c.id, { method: 'DELETE' });
				removeWidgets(c.id);
				LA.toast('Category deleted');
				load(true);
			}
		} catch (e) { LA.toast(e.message); }
	});

	// ---------------------------------------------------------------- editor
	/** A starting phrase from a log message: an error code if there is one, else the words without numbers and ids. */
	function phraseFrom(text) {
		const t = String(text || '').trim();
		const code = t.match(/\b([A-Z]{2,5}-[A-Z]{2,5}-\d{3,6}|ORA-\d{4,5})\b/);
		if (code) return { value: code[1], name: t.slice(t.indexOf(code[1]) + code[1].length).replace(/^[\s:.-]+/, '').split(/[.(\[]/)[0].trim().slice(0, 50) || code[1] };
		// ids, numbers and quoted values become * so the pattern matches every occurrence, not just this one
		const toks = t.split(/\s+/).slice(0, 10).map((w) => (/\d/.test(w) || /^["'].*["']?$/.test(w) || /^[0-9a-f]{8,}$/i.test(w) ? '*' : w));
		const pattern = toks.join(' ').replace(/(\*\s*)+/g, '* ').replace(/^\*\s*/, '').replace(/\s*\*\s*$/, '').trim();
		const name = toks.filter((w) => w !== '*').join(' ').slice(0, 50);
		return { value: pattern || t.slice(0, 40), name: name || t.slice(0, 50) };
	}

	function blank(seed) {
		const p = seed ? (/[*?]/.test(seed) || /^[A-Z]{2,5}-/.test(seed) ? { value: seed, name: seed } : phraseFrom(seed)) : null;
		return {
			name: p ? p.name : '', description: '', color: PALETTE[st.cats.length % PALETTE.length], enabled: true, matchAll: false, levels: [],
			rules: [{ field: 'message', op: p && /[*?]/.test(p.value) ? 'wildcard' : 'contains', value: p ? p.value : '', caseSensitive: false }],
			showKpi: true, chartType: 'LINE', alertEnabled: false, alertThreshold: 10, alertWindowMinutes: 15, alertSeverity: 'MEDIUM'
		};
	}

	function openEditor(cat, seed) {
		if (!ADMIN) { LA.toast('Only an administrator can create categories'); return; }
		st.editing = cat ? cat.id : null;
		st.draft = cat ? JSON.parse(JSON.stringify({ name: cat.name, description: cat.description || '', color: cat.color, enabled: cat.enabled, matchAll: cat.matchAll,
			levels: cat.levels || [], rules: cat.rules.length ? cat.rules : blank().rules, showKpi: cat.showKpi, chartType: cat.chartType, alertEnabled: cat.alertEnabled,
			alertThreshold: cat.alertThreshold, alertWindowMinutes: cat.alertWindowMinutes, alertSeverity: cat.alertSeverity })) : blank(seed);
		const box = $('catEditor');
		box.hidden = false;
		renderEditor();
		if (window.LA_SHELL) window.LA_SHELL.go('categories'); else LA.showWidget('catmanager');
		setTimeout(() => { box.scrollIntoView({ behavior: 'smooth', block: 'start' }); const n = box.querySelector('[name=name]'); if (n && !n.value) n.focus(); else { const v = box.querySelector('.cat-rule input[name=value]'); if (v) v.focus(); } }, 250);
		preview();
	}

	function closeEditor() {
		$('catEditor').hidden = true;
		$('catEditor').innerHTML = '';
		if (st.previewChart) { st.previewChart.destroy(); st.previewChart = null; }
		st.editing = null;
	}

	function renderEditor() {
		const d = st.draft;
		$('catEditor').innerHTML = `
			<div class="cat-ed-head"><b>${st.editing ? 'Edit category' : 'New category'}</b><button class="btn ghost small icon" type="button" data-ed="close" aria-label="Close">✕</button></div>
			<div class="cat-ed">
				<div class="cat-ed-form">
					<div class="cat-row2">
						<label>Name <input class="input" name="name" value="${esc(d.name)}" placeholder="e.g. Record not found" maxlength="80"></label>
						<div class="cat-colors" role="radiogroup" aria-label="Colour">${PALETTE.map((c) => `<button type="button" role="radio" aria-checked="${c === d.color}" data-color="${c}" style="--sw:${c}" aria-label="Colour ${c}"></button>`).join('')}<input type="color" name="color" value="${esc(d.color)}" title="Other colour"></div>
					</div>

					<div class="cat-sec">
						<div class="cat-sec-title">Events that match
							<span class="cat-seg" role="radiogroup" aria-label="Combine conditions">
								<button type="button" role="radio" data-match="any" aria-checked="${!d.matchAll}">any</button><button type="button" role="radio" data-match="all" aria-checked="${d.matchAll}">all</button>
							</span> of these conditions</div>
						<div class="cat-rules-ed">${d.rules.map((r, i) => ruleRow(r, i)).join('')}</div>
						<button type="button" class="btn small ghost" data-ed="add">+ Add condition</button>
						<div class="cat-hint">Use <code>*</code> for any text and <code>?</code> for one character with <i>matches</i>, e.g. <code>SBL-DAT-*</code>. Text is matched regardless of upper/lower case unless <b>Aa</b> is on.</div>
					</div>

					<div class="cat-sec">
						<div class="cat-sec-title">Levels <small>(none selected = all levels)</small></div>
						<div class="cat-levels">${LEVELS.map((l) => `<button type="button" class="lvl-chip L-${l}" aria-pressed="${d.levels.includes(l)}" data-catlevel="${l}">${l}</button>`).join('')}</div>
					</div>

					<div class="cat-sec">
						<div class="cat-sec-title">Show it as</div>
						<label class="cat-check"><input type="checkbox" name="showKpi" ${d.showKpi ? 'checked' : ''}> KPI card on the overview</label>
						<div class="cat-seg wide" role="radiogroup" aria-label="Chart">${Object.entries(CHARTS).map(([k, v]) => `<button type="button" role="radio" data-chart="${k}" aria-checked="${d.chartType === k}">${v}</button>`).join('')}</div>
					</div>

					<div class="cat-sec">
						<label class="cat-check"><input type="checkbox" name="alertEnabled" ${d.alertEnabled ? 'checked' : ''}> Open an incident when it spikes</label>
						<div class="cat-alert" ${d.alertEnabled ? '' : 'hidden'}>
							at least <input class="input num" type="number" min="1" name="alertThreshold" value="${d.alertThreshold}"> events in
							<select class="input" name="alertWindowMinutes">${[5, 10, 15, 30, 60, 240, 1440].map((m) => `<option value="${m}" ${m === d.alertWindowMinutes ? 'selected' : ''}>${m < 60 ? m + ' minutes' : m === 60 ? '1 hour' : m === 1440 ? '24 hours' : m / 60 + ' hours'}</option>`).join('')}</select>
							· severity <select class="input" name="alertSeverity">${['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((s) => `<option ${s === d.alertSeverity ? 'selected' : ''}>${s}</option>`).join('')}</select>
						</div>
					</div>

					<label class="cat-desc">Note <small>(optional)</small> <input class="input" name="description" value="${esc(d.description)}" placeholder="What it means, who to call…" maxlength="400"></label>

					<div class="cat-ed-actions">
						<span class="cat-ed-err" id="catErr"></span>
						<button type="button" class="btn ghost" data-ed="close">Cancel</button>
						<button type="button" class="btn primary" data-ed="save">${st.editing ? 'Save changes' : 'Create category'}</button>
					</div>
				</div>
				<div class="cat-ed-preview">
					<div class="cat-pv-head"><span>Live preview · ${esc(LA.RANGE_LABEL[LA.range] || LA.range)}</span><span id="catPvState"></span></div>
					<div class="cat-pv-count"><b id="catPvCount">—</b> <span id="catPvDelta"></span><small id="catPvSub"></small></div>
					<div class="cat-pv-chart"><canvas id="catPvChart"></canvas></div>
					<div class="cat-pv-samples" id="catPvSamples"></div>
				</div>
			</div>`;
	}

	function ruleRow(r, i) {
		return `<div class="cat-rule" data-i="${i}">
			<select class="input" name="field" aria-label="Field">${Object.entries(FIELDS).map(([k, v]) => `<option value="${k}" ${k === (r.field || 'message') ? 'selected' : ''}>${v}</option>`).join('')}</select>
			<select class="input" name="op" aria-label="Condition">${Object.entries(OPS).map(([k, v]) => `<option value="${k}" ${k === (r.op || 'contains') ? 'selected' : ''}>${v}</option>`).join('')}</select>
			<input class="input" name="value" value="${esc(r.value)}" placeholder="${r.op === 'regex' ? 'e.g. SBL-(DAT|EAI)-\\d+' : r.op === 'wildcard' ? 'e.g. SBL-DAT-*' : 'e.g. Record not found'}" spellcheck="false">
			<button type="button" class="cat-case" aria-pressed="${!!r.caseSensitive}" title="Match upper/lower case exactly" data-ed="case">Aa</button>
			<button type="button" class="cat-del" title="Remove condition" aria-label="Remove condition" data-ed="del" ${st.draft.rules.length < 2 ? 'disabled' : ''}>✕</button>
		</div>`;
	}

	function readForm() {
		const box = $('catEditor'), d = st.draft;
		const v = (n) => { const el = box.querySelector(`[name=${n}]`); return el ? el.value : ''; };
		d.name = v('name');
		d.description = v('description');
		d.showKpi = box.querySelector('[name=showKpi]').checked;
		d.alertEnabled = box.querySelector('[name=alertEnabled]').checked;
		d.alertThreshold = Number(v('alertThreshold')) || 1;
		d.alertWindowMinutes = Number(v('alertWindowMinutes'));
		d.alertSeverity = v('alertSeverity');
		d.rules = [...box.querySelectorAll('.cat-rule')].map((row) => ({
			field: row.querySelector('[name=field]').value, op: row.querySelector('[name=op]').value,
			value: row.querySelector('[name=value]').value, caseSensitive: row.querySelector('.cat-case').getAttribute('aria-pressed') === 'true'
		}));
		return d;
	}

	$('catEditor') && $('catEditor').addEventListener('input', (ev) => {
		if (ev.target.name === 'color') { st.draft.color = ev.target.value; markColor(); }
		if (ev.target.name === 'alertEnabled') $('catEditor').querySelector('.cat-alert').hidden = !ev.target.checked;
		readForm();
		if (['value', 'field', 'op', 'name'].includes(ev.target.name) || ev.target.closest('.cat-rule')) preview();
	});
	$('catEditor') && $('catEditor').addEventListener('change', (ev) => {
		readForm();
		if (ev.target.name === 'op') { renderEditor(); preview(); }
	});
	function markColor() {
		$('catEditor').querySelectorAll('[data-color]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.color === st.draft.color)));
		if (st.previewChart) { st.previewChart.data.datasets = [dataset(Object.assign({}, st.draft, { series: st.previewChart.data.datasets[0].data }), st.draft.chartType === 'NONE' ? 'LINE' : st.draft.chartType)]; st.previewChart.update('none'); }
	}
	$('catEditor') && $('catEditor').addEventListener('click', (ev) => {
		const b = ev.target.closest('button');
		if (!b) return;
		const d = readForm();
		if (b.dataset.color) { d.color = b.dataset.color; $('catEditor').querySelector('[name=color]').value = d.color; markColor(); return; }
		if (b.dataset.match) { d.matchAll = b.dataset.match === 'all'; b.parentElement.querySelectorAll('button').forEach((x) => x.setAttribute('aria-checked', String(x === b))); preview(); return; }
		if (b.dataset.catlevel) { const on = b.getAttribute('aria-pressed') !== 'true'; b.setAttribute('aria-pressed', String(on)); d.levels = on ? [...d.levels, b.dataset.catlevel] : d.levels.filter((l) => l !== b.dataset.catlevel); preview(); return; }
		if (b.dataset.chart) { d.chartType = b.dataset.chart; b.parentElement.querySelectorAll('button').forEach((x) => x.setAttribute('aria-checked', String(x === b))); preview(true); return; }
		const act = b.dataset.ed;
		if (act === 'close') closeEditor();
		if (act === 'add') { d.rules.push({ field: 'message', op: 'contains', value: '', caseSensitive: false }); renderEditor(); const ins = $('catEditor').querySelectorAll('.cat-rule input[name=value]'); ins[ins.length - 1].focus(); preview(); }
		if (act === 'del') { d.rules.splice(Number(b.closest('.cat-rule').dataset.i), 1); renderEditor(); preview(); }
		if (act === 'case') { b.setAttribute('aria-pressed', String(b.getAttribute('aria-pressed') !== 'true')); readForm(); preview(); }
		if (act === 'save') save(d, st.editing);
	});

	let pvTimer = 0, pvSeq = 0;
	function preview(now) {
		clearTimeout(pvTimer);
		pvTimer = setTimeout(runPreview, now ? 0 : 350);
	}
	async function runPreview() {
		const d = st.draft, seq = ++pvSeq;
		if (!$('catPvCount')) return;
		if (!d.rules.some((r) => r.value)) {
			$('catPvCount').textContent = '—';
			$('catPvSub').textContent = ' Type what to look for';
			$('catPvDelta').innerHTML = '';
			$('catPvSamples').innerHTML = '';
			return;
		}
		$('catPvState').innerHTML = '<span class="src-spin"></span>';
		try {
			const res = await fetch(`/api/categories/preview?range=${encodeURIComponent(LA.range)}&tz=${LA.TZ_OFFSET}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(Object.assign({}, d, { name: d.name || 'Preview' })) });
			const r = await res.json();
			if (seq !== pvSeq || !$('catPvCount')) return;
			$('catPvState').textContent = '';
			if (!res.ok) { $('catPvCount').textContent = '—'; $('catPvSub').textContent = ' ' + (r.error || 'Check the conditions'); $('catPvSamples').innerHTML = ''; return; }
			const c = r.categories[0];
			st.times = r.times; st.bucket = r.bucketMinutes;
			$('catPvCount').textContent = nf.format(c.count);
			$('catPvDelta').innerHTML = change(c);
			$('catPvSub').textContent = ` matching events${c.errors ? ` · ${nf.format(c.errors)} errors` : ''}${c.topServices.length ? ' · mostly ' + c.topServices[0].name : ''}`;
			const type = d.chartType === 'NONE' ? 'LINE' : d.chartType;
			st.previewChart = upsertChart('preview', $('catPvChart'), type, [dataset(Object.assign({}, d, { series: c.series }), type)]);
			$('catPvSamples').innerHTML = r.samples.length ? '<div class="cat-pv-title">Latest matching events</div>' + r.samples.map((x) => `<div class="cat-pv-row"><span class="lvl L-${esc(x.level)}">${esc(x.level)}</span><span class="t">${new Date(x.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span><span class="m" title="${esc(x.message)}">${esc(x.message)}</span></div>`).join('')
				: '<div class="cat-pv-none">Nothing matches in this period yet. The category still counts new events as they arrive.</div>';
		} catch (e) { if (seq === pvSeq) $('catPvState').textContent = ''; }
	}

	async function save(d, id, quiet) {
		const err = $('catErr');
		try {
			const body = JSON.stringify({ name: d.name, description: d.description, color: d.color, enabled: d.enabled, matchAll: d.matchAll, levels: d.levels, rules: d.rules,
				showKpi: d.showKpi, chartType: d.chartType, alertEnabled: d.alertEnabled, alertThreshold: d.alertThreshold, alertWindowMinutes: d.alertWindowMinutes, alertSeverity: d.alertSeverity });
			const res = await fetch(id ? '/api/categories/' + id : '/api/categories', { method: id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body });
			const r = await res.json().catch(() => ({}));
			if (!res.ok) throw new Error(r.error || (res.status === 403 ? 'Only an administrator can change categories' : 'Could not save'));
			if (!quiet) {
				closeEditor();
				LA.toast(id ? `Saved “${r.name}”` : `Created “${r.name}”${r.showKpi || r.chartType !== 'NONE' ? ' · added to the overview' : ''}`);
			}
			await load(true);
		} catch (e) { if (err && !quiet) err.textContent = e.message; else LA.toast(e.message); }
	}

	// ---------------------------------------------------------------- comparison chart
	function renderCompare() {
		const canvas = $('catCompareChart');
		if (!canvas) return;
		const cats = st.cats.filter((c) => c.enabled);
		$('catCompareEmpty').hidden = cats.length > 0;
		$('catCompareWrap').hidden = !cats.length;
		$('catLegend').innerHTML = cats.map((c) => `<button class="cat-leg" data-leg="${c.id}" style="--cc:${esc(c.color)}" title="Show these logs"><i></i>${esc(c.name)} <b>${nf.format(c.count)}</b></button>`).join('');
		if (!cats.length) return;
		upsertChart('compare', canvas, 'LINE', cats.map((c) => Object.assign(dataset(c, 'LINE'), { catId: c.id })), (e, els, chart) => {
			const hit = chart.getElementsAtEventForMode(e, 'nearest', { intersect: false }, true)[0];
			if (hit) showLogs(chart.data.datasets[hit.datasetIndex].catId);
		});
	}
	document.addEventListener('click', (ev) => { const l = ev.target.closest('[data-leg]'); if (l) showLogs(Number(l.dataset.leg)); });

	// ---------------------------------------------------------------- one KPI card and one chart per category
	function overviewGrid() { const k = document.querySelector('[data-widget="kpi-total"]'); return k && k.parentElement; }

	function removeWidgets(id) {
		['catk-' + id, 'catc-' + id].forEach((w) => { if (st.charts[w]) { st.charts[w].destroy(); delete st.charts[w]; } LA.unregisterWidget(w); });
	}

	function renderWidgets() {
		const grid = overviewGrid();
		if (!grid) return;
		let added = false;
		const keep = new Set();
		st.cats.forEach((c) => {
			if (!c.enabled) return;
			if (c.showKpi) { keep.add('catk-' + c.id); added = kpi(grid, c) || added; }
			if (c.chartType !== 'NONE') { keep.add('catc-' + c.id); added = chart(grid, c) || added; }
		});
		document.querySelectorAll('[data-widget^="catk-"], [data-widget^="catc-"]').forEach((el) => {
			const id = el.dataset.widget;
			if (!keep.has(id)) { if (st.charts[id]) { st.charts[id].destroy(); delete st.charts[id]; } LA.unregisterWidget(id); }
		});
		if (added) { LA.applyLayout(); LA.renderWidgetList(); }
	}

	function kpi(grid, c) {
		const id = 'catk-' + c.id;
		let el = document.querySelector(`[data-widget="${id}"]`), fresh = false;
		if (!el) {
			el = document.createElement('section');
			el.dataset.widget = id;
			el.className = 'card kpi span-3 cat-kpi';
			el.innerHTML = `<div class="kpi-top"><span class="kpi-label"></span><span class="kpi-icon"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/></svg></span></div>
				<div class="kpi-value"></div><div class="kpi-delta"></div><div class="cat-kpi-spark"></div><div class="kpi-foot"><span class="cat-kpi-foot"></span><span class="kpi-share"></span></div>`;
			el.title = 'Click to see these logs';
			el.addEventListener('click', (e) => { if (!e.target.closest('.w-grip, .w-resize, .w-remove')) showLogs(Number(el.dataset.cat)); });
			grid.appendChild(el);
			LA.registerWidget({ id, group: 'Your categories', name: c.name + ' · KPI', desc: 'Your category as a KPI card', icon: '#', after: (order) => [...order].reverse().find((x) => x.startsWith('catk-')) || 'kpi-incidents' }, el);
			fresh = true;
		} else {
			LA.registerWidget({ id, group: 'Your categories', name: c.name + ' · KPI', desc: 'Your category as a KPI card', icon: '#' }, el);
		}
		el.dataset.cat = c.id;
		el.style.setProperty('--c', c.color);
		el.querySelector('.kpi-label').textContent = c.name;
		el.querySelector('.kpi-value').textContent = nf.format(c.count);
		el.querySelector('.kpi-delta').innerHTML = change(c) || '&nbsp;';
		el.querySelector('.cat-kpi-spark').innerHTML = spark(c.series, c.color, 240, 30);
		el.querySelector('.cat-kpi-foot').textContent = c.lastSeen ? 'Last ' + ago(c.lastSeen) : 'No matches yet';
		el.querySelector('.kpi-share').textContent = c.topServices.length ? c.topServices[0].name : '';
		return fresh;
	}

	function chart(grid, c) {
		const id = 'catc-' + c.id;
		let el = document.querySelector(`[data-widget="${id}"]`), fresh = false;
		if (!el) {
			el = document.createElement('section');
			el.dataset.widget = id;
			el.className = 'card span-6 cat-chart-card';
			el.innerHTML = `<div class="card-head"><div><div class="card-title"><i class="cat-dot"></i><span class="cc-name"></span></div><div class="card-sub cc-sub"></div></div><div class="cc-stat"></div></div>
				<div class="card-body"><div class="chart-wrap"><canvas></canvas></div><div class="cc-svcs"></div></div>`;
			grid.appendChild(el);
			LA.registerWidget({ id, group: 'Your categories', name: c.name + ' · chart', desc: 'Your category over time', icon: '⌁', after: (order) => [...order].reverse().find((x) => x.startsWith('catc-')) || 'catcompare' }, el);
			fresh = true;
		} else {
			LA.registerWidget({ id, group: 'Your categories', name: c.name + ' · chart', desc: 'Your category over time', icon: '⌁' }, el);
		}
		el.style.setProperty('--cc', c.color);
		el.querySelector('.cc-name').textContent = c.name;
		el.querySelector('.cc-sub').innerHTML = describe(c);
		el.querySelector('.cc-stat').innerHTML = `<b>${nf.format(c.count)}</b> ${change(c)}`;
		el.querySelector('.cc-svcs').innerHTML = c.topServices.map((s) => `<span class="chip">${esc(s.name)} · ${nf.format(s.count)}</span>`).join('') + `<button class="btn small ghost" data-cat-logs="${c.id}">View logs →</button>`;
		upsertChart(id, el.querySelector('canvas'), c.chartType, [dataset(c, c.chartType)], () => showLogs(c.id));
		return fresh;
	}
	document.addEventListener('click', (ev) => { const b = ev.target.closest('[data-cat-logs]'); if (b) showLogs(Number(b.dataset.catLogs)); });

	load(true);
})();
