/* Log sources: connect Siebel log folders and databases. */
(function () {
	'use strict';
	const $ = (id) => document.getElementById(id);
	const form = $('form');
	const esc = (s) => s == null ? '' : String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
	const nf = new Intl.NumberFormat('en-US');
	const DESKTOP = !!window.APP_DESKTOP;
	const PORTS = { SQLSERVER: 1433, ORACLE: 1521, POSTGRES: 5432, MYSQL: 3306 };
	const MAP_FIELDS = ['tsColumn', 'messageColumn', 'levelColumn', 'serviceColumn', 'traceColumn', 'hostColumn', 'rawColumn', 'idColumn'];
	const st = { list: [], editing: null, type: 'FOLDER', tested: null, info: null };

	function toast(m) { const t = $('toast'); t.textContent = m; t.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 3200); }
	async function api(path, opts) {
		const res = await fetch(path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts || {}));
		if (res.status === 204) return null;
		const body = await res.json().catch(() => ({}));
		if (!res.ok) throw new Error(body.error || res.status + ' ' + res.statusText);
		return body;
	}
	function ago(ms) {
		if (!ms) return 'never';
		const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
		return s < 60 ? s + 's ago' : s < 3600 ? Math.round(s / 60) + 'm ago' : Math.round(s / 3600) + 'h ago';
	}
	const size = (b) => b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : b > 1e6 ? (b / 1e6).toFixed(1) + ' MB' : b > 1e3 ? Math.round(b / 1e3) + ' KB' : b + ' B';
	const STATUS = { RUNNING: 'Reading', STARTING: 'Starting', PAUSED: 'Paused', ERROR: 'Problem' };

	// ---------------------------------------------------------------- list
	async function refresh() {
		try { st.list = await api('/api/sources'); } catch (e) { return; }
		const welcome = !st.list.length;
		$('welcome').hidden = !welcome && !location.search.includes('welcome');
		if (!welcome && location.search.includes('welcome') && !refresh.cleared) { refresh.cleared = true; }
		$('listCard').hidden = welcome;
		const events = st.list.reduce((a, s) => a + s.eventsIngested, 0);
		$('listSub').textContent = `${st.list.length} source${st.list.length === 1 ? '' : 's'} · ${nf.format(events)} events read · checked every few seconds`;
		$('list').innerHTML = st.list.map((s) => `
			<div class="src-card" data-id="${s.id}">
				<div class="src-card-top"><b>${esc(s.name)}</b><span class="src-type">${s.type === 'FOLDER' ? 'FOLDER' : esc(s.dbType || 'DATABASE')}</span>${s.demo ? '<span class="src-type">SAMPLE</span>' : ''}<span class="src-status ${s.status}"><i></i>${STATUS[s.status]}</span></div>
				<div class="src-loc">${esc(s.location)}</div>
				<div class="src-stats"><span><b>${nf.format(s.eventsIngested)}</b> events read</span>${s.type === 'FOLDER' ? `<span><b>${s.filesTracked}</b> files followed</span>` : `<span>every <b>${s.pollSeconds}s</b></span>`}<span>last check <b>${ago(s.lastRunAt)}</b></span></div>
				<div class="src-btns">
					<button class="btn small" data-act="scan">Check now</button>
					<button class="btn small" data-act="toggle">${s.enabled ? 'Pause' : 'Resume'}</button>
					<button class="btn small" data-act="edit">Edit</button>
					<button class="btn ghost small" data-act="delete">Remove</button>
				</div>
				${s.lastError && s.enabled ? `<div class="src-err">${esc(s.lastError)}</div>` : ''}
			</div>`).join('');
	}

	$('list').addEventListener('click', async (ev) => {
		const b = ev.target.closest('[data-act]');
		if (!b) return;
		const id = Number(b.closest('[data-id]').dataset.id), s = st.list.find((x) => x.id === id);
		try {
			if (b.dataset.act === 'scan') { b.disabled = true; const r = await api(`/api/sources/${id}/scan`, { method: 'POST' }); toast(`${s.name}: ${nf.format(r.lastScanEvents)} new events`); }
			if (b.dataset.act === 'toggle') await api(`/api/sources/${id}/enabled?value=${!s.enabled}`, { method: 'POST' });
			if (b.dataset.act === 'edit') openEditor(s.type, s);
			if (b.dataset.act === 'delete') {
				if (!confirm(`Remove “${s.name}”? Logs already read stay on the dashboard.`)) return;
				await api(`/api/sources/${id}`, { method: 'DELETE' });
				toast('Source removed');
			}
		} catch (e) { toast('Failed: ' + e.message); }
		refresh();
	});

	// ---------------------------------------------------------------- editor
	function openEditor(type, src) {
		st.type = type;
		st.editing = src || null;
		st.tested = null;
		form.reset();
		$('result').hidden = true;
		$('mapping').hidden = true;
		$('testState').textContent = '';
		$('testState').className = 'src-test-state';
		document.querySelectorAll('.f-folder').forEach((e) => { e.hidden = type !== 'FOLDER'; });
		document.querySelectorAll('.f-db').forEach((e) => { e.hidden = type !== 'DATABASE'; });
		$('edTitle').textContent = src ? 'Edit ' + src.name : type === 'FOLDER' ? 'New log folder' : 'New database source';
		$('edSub').textContent = type === 'FOLDER'
			? 'Every file matching the pattern is followed; only new lines are read on each check. Siebel files are recognised from their header.'
			: 'Use a read-only account. Only rows newer than the last one read are fetched on each check.';
		$('browseBtn').hidden = !(DESKTOP && st.info && st.info.folderPicker);
		if (src) {
			for (const el of form.elements) {
				if (!el.name || !(el.name in src)) continue;
				if (el.type === 'checkbox') el.checked = !!src[el.name];
				else if (el.tagName === 'SELECT' && MAP_FIELDS.includes(el.name)) setOptions(el, src[el.name] ? [src[el.name]] : [], src[el.name]);
				else if (el.name !== 'password') el.value = src[el.name] == null ? '' : src[el.name];
			}
			if (type === 'DATABASE') {
				form.elements.backfillDaysDb.value = String(src.backfillDays);
				if (src.jdbcUrl) form.elements.dbType.value = 'CUSTOM';
				form.elements.password.placeholder = src.hasPassword ? 'saved (leave empty to keep)' : '';
				$('mapping').hidden = false;
			}
		} else if (type === 'DATABASE') {
			form.elements.dbType.value = 'SQLSERVER';
		}
		syncDbType();
		$('editor').hidden = false;
		$('editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
		setTimeout(() => (type === 'FOLDER' ? form.elements.path : form.elements.host).focus(), 300);
	}

	function closeEditor() { $('editor').hidden = true; st.editing = null; }

	function syncDbType() {
		const t = form.elements.dbType.value, custom = t === 'CUSTOM', h2 = t === 'H2';
		document.querySelectorAll('.f-url').forEach((e) => { e.hidden = st.type !== 'DATABASE' || !custom; });
		document.querySelectorAll('.f-hostport').forEach((e) => { e.hidden = st.type !== 'DATABASE' || custom || (h2 && !e.querySelector('[name=databaseName]')); });
		form.elements.port.placeholder = PORTS[t] || '';
		form.elements.databaseName.placeholder = t === 'ORACLE' ? 'service name, e.g. SBLPROD' : h2 ? 'file path, e.g. C:/data/logs' : 'database name';
	}
	form.elements.dbType.addEventListener('change', syncDbType);

	function payload() {
		const f = form.elements, db = st.type === 'DATABASE', v = (n) => (f[n] && f[n].value.trim()) || null;
		const p = { type: st.type, name: v('name') };
		if (!db) Object.assign(p, { path: v('path'), filePattern: v('filePattern') || '*.log', recursive: f.recursive.checked, backfillDays: Number(f.backfillDays.value), defaultService: v('defaultService') });
		else {
			const custom = f.dbType.value === 'CUSTOM';
			Object.assign(p, {
				dbType: custom ? null : f.dbType.value, jdbcUrl: custom ? v('jdbcUrl') : null, host: custom ? null : v('host'), port: custom || !v('port') ? null : Number(v('port')),
				databaseName: custom ? null : v('databaseName'), username: v('username'), password: f.password.value || null, queryText: v('queryText'),
				pollSeconds: Number(f.pollSeconds.value), backfillDays: Number(f.backfillDaysDb.value)
			});
			MAP_FIELDS.forEach((n) => { p[n] = v(n); });
		}
		return p;
	}

	function setOptions(select, cols, chosen) {
		const required = select.name === 'tsColumn' || select.name === 'messageColumn';
		select.innerHTML = (required ? '<option value="">— choose —</option>' : '<option value="">(none)</option>') + cols.map((c) => `<option${c === chosen ? ' selected' : ''}>${esc(c)}</option>`).join('');
	}

	async function test() {
		const s = $('testState');
		s.className = 'src-test-state';
		s.textContent = st.type === 'FOLDER' ? 'Looking at the folder…' : 'Connecting…';
		$('testBtn').disabled = true;
		try {
			const r = await api('/api/sources/test' + (st.editing ? '?id=' + st.editing.id : ''), { method: 'POST', body: JSON.stringify(payload()) });
			st.tested = r;
			if (!r.ok) { s.className = 'src-test-state bad'; s.textContent = r.error; $('result').hidden = true; return; }
			s.className = 'src-test-state ok';
			if (st.type === 'FOLDER') {
				s.textContent = r.files ? `✓ ${r.files} matching file${r.files === 1 ? '' : 's'} (${size(r.totalBytes)})` : '✓ Folder found, but no files match the pattern yet';
				renderFolderResult(r);
			} else {
				s.textContent = `✓ Connected: ${r.product}`;
				renderDbResult(r);
			}
		} catch (e) { s.className = 'src-test-state bad'; s.textContent = e.message; }
		finally { $('testBtn').disabled = false; }
	}

	function renderFolderResult(r) {
		const res = $('result');
		res.hidden = false;
		res.innerHTML = `
			${r.newest.length ? `<h4>Newest files</h4><div class="src-files">${r.newest.map((f) => `<div>${esc(f.name)}<span>${size(f.size)} · ${new Date(f.modified).toLocaleString()}</span></div>`).join('')}</div>` : ''}
			${r.preview.length ? `<h4>How the first lines of ${esc(r.previewFile)} are read</h4>
				<div class="src-chips">${Object.entries(r.formats).map(([k, n]) => `<span class="chip">${esc(k)} · ${n}</span>`).join('')}</div>
				<div class="src-scroll"><table class="src-preview"><thead><tr><th>Time</th><th>Level</th><th>Service</th><th>Message</th></tr></thead><tbody>
				${r.preview.map((p) => `<tr><td>${p.time ? new Date(p.time).toLocaleTimeString() : ''}</td><td><span class="lvl lvl-${esc(p.level)}">${esc(p.level)}</span></td><td>${esc(p.service)}</td><td class="msg">${esc(p.message)}</td></tr>`).join('')}
				</tbody></table></div>` : ''}`;
	}

	function renderDbResult(r) {
		const res = $('result');
		res.hidden = false;
		res.innerHTML = `<h4>First rows</h4><div class="src-scroll"><table class="src-preview db"><thead><tr>${r.columns.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>
			<tbody>${r.rows.map((row) => `<tr>${row.map((v) => `<td class="msg" title="${esc(v)}">${esc(v)}</td>`).join('')}</tr>`).join('') || `<tr><td colspan="${r.columns.length}" class="empty">The table is empty</td></tr>`}</tbody></table></div>`;
		$('mapping').hidden = false;
		MAP_FIELDS.forEach((n) => {
			const el = form.elements[n], current = el.value;
			setOptions(el, r.columns, current && r.columns.includes(current) ? current : r.suggested[n]);
		});
	}

	async function save() {
		const p = payload();
		try {
			$('saveBtn').disabled = true;
			const s = st.editing ? await api('/api/sources/' + st.editing.id, { method: 'PUT', body: JSON.stringify(p) }) : await api('/api/sources', { method: 'POST', body: JSON.stringify(p) });
			closeEditor();
			toast(`Saved. Reading ${s.name}…`);
			api(`/api/sources/${s.id}/scan`, { method: 'POST' }).then(refresh).catch(() => {});
			refresh();
		} catch (e) { toast(e.message); } finally { $('saveBtn').disabled = false; }
	}

	// ---------------------------------------------------------------- wiring
	document.addEventListener('click', (ev) => {
		const b = ev.target.closest('[data-new]');
		if (b) openEditor(b.dataset.new);
	});
	$('edClose').addEventListener('click', closeEditor);
	$('cancelBtn').addEventListener('click', closeEditor);
	$('testBtn').addEventListener('click', test);
	$('saveBtn').addEventListener('click', save);
	$('browseBtn').addEventListener('click', async () => {
		try {
			const r = await api('/api/desktop/browse' + (form.elements.path.value ? '?start=' + encodeURIComponent(form.elements.path.value) : ''), { method: 'POST' });
			if (r.path) { form.elements.path.value = r.path; if (!form.elements.name.value) form.elements.name.value = r.path.split(/[\\/]/).filter(Boolean).slice(-2).join(' / '); test(); }
		} catch (e) { toast(e.message); }
	});
	$('sampleBtn').addEventListener('click', async () => {
		$('sampleBtn').disabled = true;
		try {
			const s = await api('/api/sources/sample', { method: 'POST' });
			toast(`Sample Siebel logs are being written to ${s.path}`);
			await refresh();
			setTimeout(() => { location.href = '/'; }, 1200);
		} catch (e) { toast('Failed: ' + e.message); $('sampleBtn').disabled = false; }
	});

	if (DESKTOP) api('/api/desktop/info').then((i) => { st.info = i; }).catch(() => {});
	refresh();
	setInterval(() => { if (!document.hidden) refresh(); }, 3000);
})();
