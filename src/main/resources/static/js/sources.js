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
	const ROLE_LABEL = { tsColumn: 'time', messageColumn: 'message', levelColumn: 'level', serviceColumn: 'component', traceColumn: 'trace', hostColumn: 'host', rawColumn: 'raw line', idColumn: 'id' };
	const TYPE_LABEL = { FOLDER: 'FOLDER', FILE: 'EXCEL / CSV' };
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
				<div class="src-card-top"><b>${esc(s.name)}</b><span class="src-type">${TYPE_LABEL[s.type] || esc(s.dbType || 'DATABASE')}</span>${s.demo ? '<span class="src-type">SAMPLE</span>' : ''}<span class="src-status ${s.status}"><i></i>${STATUS[s.status]}</span></div>
				<div class="src-loc">${esc(s.location)}</div>
				<div class="src-stats"><span><b>${nf.format(s.eventsIngested)}</b> events read</span>${s.type === 'FOLDER' ? `<span><b>${s.filesTracked}</b> files followed</span>` : s.type === 'FILE' ? `<span>re-read when the file is saved</span>` : `<span>every <b>${s.pollSeconds}s</b></span>`}<span>last check <b>${ago(s.lastRunAt)}</b></span></div>
				<div class="src-btns">
					<button class="btn small" data-act="scan">Check now</button>
					<button class="btn small" data-act="toggle">${s.enabled ? 'Pause' : 'Resume'}</button>
					<button class="btn small" data-act="edit">Edit</button>
					<button class="btn ghost small" data-act="delete">Remove</button>
				</div>
				${s.lastError && s.enabled ? `<div class="src-err">${esc(s.lastError)}</div>${s.hint ? `<div class="src-hint"><b>What to check:</b> ${esc(s.hint)}</div>` : ''}` : ''}
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
				if (!(await LA_UI.confirm({ title: 'Remove this log source?', message: `“${s.name}” will no longer be read. Logs already read stay on the dashboard.`, confirm: 'Remove', danger: true, icon: 'trash' }))) return;
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
		$('discover').hidden = true;
		$('autoBadge').hidden = true;
		clearConfidence();
		st.suggested = null;
		st.detected = null;
		setColMode(src ? src.autoColumns !== false : true, true);
		MAP_FIELDS.forEach((n) => setOptions(form.elements[n], [], ''));
		setOptions(form.elements.sheet, [], '');
		$('testState').textContent = '';
		$('testState').className = 'src-test-state';
		document.querySelectorAll('.f-folder').forEach((e) => { e.hidden = type !== 'FOLDER'; });
		document.querySelectorAll('.f-db').forEach((e) => { e.hidden = type !== 'DATABASE'; });
		document.querySelectorAll('.f-file').forEach((e) => { e.hidden = type !== 'FILE'; });
		$('edTitle').textContent = src ? 'Edit ' + src.name : { FOLDER: 'New log folder', DATABASE: 'New database source', FILE: 'New Excel / CSV source' }[type];
		$('edSub').textContent = {
			FOLDER: 'Every file matching the pattern is followed; only new lines are read on each check. Siebel files are recognised from their header.',
			DATABASE: 'Use a read-only account. Connect, then let Log Analyzer find the log tables and recognise the columns. Only new rows are fetched on each check.',
			FILE: 'Pick a workbook or CSV export. The sheet, header row and columns are recognised automatically; rows added later are read when the file is saved.'
		}[type];
		const picker = DESKTOP && st.info && st.info.folderPicker;
		$('browseBtn').hidden = !picker;
		$('browseFileBtn').hidden = !picker;
		if (src) {
			for (const el of form.elements) {
				if (!el.name || !(el.name in src)) continue;
				if (el.type === 'checkbox') el.checked = !!src[el.name];
				else if (el.tagName === 'SELECT' && MAP_FIELDS.includes(el.name)) setOptions(el, src[el.name] ? [src[el.name]] : [], src[el.name]);
				else if (el.name !== 'password') el.value = src[el.name] == null ? '' : src[el.name];
			}
			if (type !== 'FOLDER') $('mapSub').textContent = st.auto ? 'Saved columns. Press Test to check them against the data again.' : 'Saved columns. Press Test to load the column list.';
			if (type === 'FILE') {
				form.elements.filePath.value = src.path || '';
				form.elements.fileService.value = src.defaultService || '';
				setOptions(form.elements.sheet, src.queryText ? [src.queryText] : [], src.queryText || '');
				$('mapping').hidden = false;
			}
			if (type === 'DATABASE') {
				form.elements.backfillDaysDb.value = String(src.backfillDays);
				if (src.jdbcUrl) form.elements.dbType.value = 'CUSTOM';
				form.elements.password.placeholder = src.hasPassword ? 'saved (leave empty to keep)' : '';
				$('mapping').hidden = false;
			}
		} else if (type === 'DATABASE') {
			form.elements.dbType.value = 'ORACLE';
			form.elements.sslMode.value = 'AUTO';
		}
		if (type !== 'FOLDER') $('mapping').hidden = false; // the column choice is offered from the start
		syncDbType();
		st.lastQuery = src ? locationKey() : null;
		$('editor').hidden = false;
		$('editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
		setTimeout(() => ({ FOLDER: form.elements.path, FILE: form.elements.filePath, DATABASE: form.elements.host })[type].focus(), 300);
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
		if (st.type === 'FILE') {
			Object.assign(p, { path: v('filePath'), queryText: v('sheet'), defaultService: v('fileService'), pollSeconds: Number(f.pollSeconds.value), backfillDays: 1, autoColumns: st.auto });
			MAP_FIELDS.forEach((n) => { p[n] = v(n); });
		} else if (!db) Object.assign(p, { path: v('path'), filePattern: v('filePattern') || '*.log', recursive: f.recursive.checked, backfillDays: Number(f.backfillDays.value), defaultService: v('defaultService') });
		else {
			const custom = f.dbType.value === 'CUSTOM';
			Object.assign(p, {
				dbType: custom ? null : f.dbType.value, jdbcUrl: custom ? v('jdbcUrl') : null, host: custom ? null : v('host'), port: custom || !v('port') ? null : Number(v('port')),
				databaseName: custom ? null : v('databaseName'), username: v('username'), password: f.password.value || null, queryText: v('queryText'),
				pollSeconds: Number(f.pollSeconds.value), backfillDays: Number(f.backfillDaysDb.value), sslMode: f.sslMode.value, autoColumns: st.auto
			});
			MAP_FIELDS.forEach((n) => { p[n] = v(n); });
		}
		return p;
	}

	function setOptions(select, cols, chosen) {
		const required = select.name === 'tsColumn' || select.name === 'messageColumn';
		const empty = select.name === 'sheet' ? 'Find automatically' : required ? '— choose —' : '(none)';
		select.innerHTML = `<option value="">${empty}</option>` + cols.map((c) => `<option${c === chosen ? ' selected' : ''}>${esc(c)}</option>`).join('');
	}

	async function test() {
		const s = $('testState');
		s.className = 'src-test-state';
		s.innerHTML = '<span class="src-spin"></span>' + ({ FOLDER: 'Looking at the folder…', FILE: 'Reading the file…', DATABASE: 'Connecting and reading a sample of rows…' })[st.type];
		$('testBtn').disabled = true;
		try {
			const r = await api('/api/sources/test' + (st.editing ? '?id=' + st.editing.id : ''), { method: 'POST', body: JSON.stringify(payload()) });
			st.tested = r;
			if (!r.ok) { s.className = 'src-test-state bad'; s.textContent = r.error; showHint(r.hint); return; }
			$('hintBox') && $('hintBox').remove();
			s.className = 'src-test-state ok';
			if (st.type === 'FOLDER') {
				s.textContent = r.files ? `✓ ${r.files} matching file${r.files === 1 ? '' : 's'} (${size(r.totalBytes)})` : '✓ Folder found, but no files match the pattern yet';
				renderFolderResult(r);
			} else if (st.type === 'FILE') {
				s.textContent = `✓ ${r.product}${r.sheet ? ', sheet “' + r.sheet + '”' : ''}: ${nf.format(r.totalRows)} rows`;
				if (r.sheets && r.sheets.length) setOptions(form.elements.sheet, r.sheets, form.elements.sheet.value);
				if (!form.elements.name.value) form.elements.name.value = (form.elements.filePath.value.split(/[\\/]/).pop() || '');
				renderDbResult(r);
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
		const fresh = st.lastQuery !== locationKey();
		st.columns = r.columns;
		st.suggested = r.suggested;
		st.detected = r.detected;
		MAP_FIELDS.forEach((n) => {
			const el = form.elements[n], current = el.value, keep = current && r.columns.includes(current);
			// manual: never pre-fill; automatic: use what was recognised (a choice the user made for this table stays)
			setOptions(el, r.columns, !st.auto ? (keep ? current : '') : keep && !fresh ? current : (r.suggested[n] || (keep ? current : '')));
		});
		st.lastQuery = locationKey();
		if (st.auto) showConfidence(r.detected, r.suggested);
		else { clearConfidence(); $('autoBadge').hidden = true; $('mapSub').textContent = `Choose which of the ${r.columns.length} columns holds what. Timestamp and message are required.`; }
	}

	function showHint(hint) {
		$('result').hidden = true;
		let box = $('hintBox');
		if (!hint) { if (box) box.remove(); return; }
		if (!box) { box = document.createElement('div'); box.id = 'hintBox'; box.className = 'src-hint'; $('result').before(box); }
		box.innerHTML = `<b>What to check:</b> ${esc(hint)}`;
	}

	// ---------------------------------------------------------------- automatic / manual columns
	function setColMode(auto, quiet) {
		st.auto = auto;
		document.querySelectorAll('[data-colmode]').forEach((b) => b.setAttribute('aria-checked', String((b.dataset.colmode === 'auto') === auto)));
		if (quiet) return;
		if (auto) {
			if (st.suggested && st.columns) {
				MAP_FIELDS.forEach((n) => setOptions(form.elements[n], st.columns, st.suggested[n] || ''));
				showConfidence(st.detected, st.suggested);
			} else {
				$('mapSub').textContent = 'Press Test: the columns are recognised from the data. You can still change any of them.';
			}
		} else {
			clearConfidence();
			$('autoBadge').hidden = true;
			MAP_FIELDS.forEach((n) => setOptions(form.elements[n], st.columns || [], ''));
			$('mapSub').textContent = st.columns ? `Choose which of the ${st.columns.length} columns holds what. Timestamp and message are required.` : 'Press Test to load the column list, then choose which column holds what.';
		}
	}
	document.querySelectorAll('[data-colmode]').forEach((b) => b.addEventListener('click', () => setColMode(b.dataset.colmode === 'auto')));

	// ---------------------------------------------------------------- paste a connection string
	/** Understands Oracle (host:port/service, host:port:SID, TNS descriptors), SQL Server / Azure, PostgreSQL, MySQL and JDBC URLs. */
	function parseConnection(raw) {
		const t = raw.trim(), out = {};
		let m;
		if (/^jdbc:/i.test(t)) {
			out.dbType = 'CUSTOM'; out.jdbcUrl = t;
			if ((m = t.match(/^jdbc:oracle:thin:(?:([^/@]+)(?:\/([^@]*))?)?@\/\/?([^:/?]+)(?::(\d+))?\/([^?]+)/i))) Object.assign(out, { dbType: 'ORACLE', jdbcUrl: '', username: m[1], password: m[2], host: m[3], port: m[4], databaseName: m[5] });
			return out;
		}
		if (/^\(\s*description/i.test(t)) { out.dbType = 'CUSTOM'; out.jdbcUrl = 'jdbc:oracle:thin:@' + t.replace(/\s+/g, ''); return out; }
		if ((m = t.match(/^(?:([^/@\s]+)(?:\/([^@\s]*))?@)?\/?\/?([\w.-]+):(\d+):(\w+)$/))) { // Oracle SID form
			out.dbType = 'CUSTOM'; out.jdbcUrl = `jdbc:oracle:thin:@${m[3]}:${m[4]}:${m[5]}`; out.username = m[1]; out.password = m[2]; return out;
		}
		if ((m = t.match(/^(?:([^/@\s]+)(?:\/([^@\s]*))?@)?\/?\/?([\w.-]+)(?::(\d+))?\/([\w.$#-]+)$/))) { // Oracle Easy Connect: [user[/pw]@]host[:port]/service
			return { dbType: 'ORACLE', host: m[3], port: m[4], databaseName: m[5], username: m[1], password: m[2] };
		}
		if ((m = t.match(/^(postgres(?:ql)?|mysql):\/\/(?:([^:@/]+)(?::([^@/]*))?@)?([^:/?]+)(?::(\d+))?\/([^?]+)(?:\?(.*))?$/i))) {
			const q = (m[7] || '').toLowerCase();
			return { dbType: m[1].toLowerCase() === 'mysql' ? 'MYSQL' : 'POSTGRES', username: m[2] && decodeURIComponent(m[2]), password: m[3] && decodeURIComponent(m[3]), host: m[4], port: m[5], databaseName: decodeURIComponent(m[6]),
				sslMode: /sslmode=(require|verify-ca)|sslmode=required|ssl=true/.test(q) ? 'REQUIRE' : /verify-full|verify_identity/.test(q) ? 'VERIFY' : undefined };
		}
		if (/(server|data source|address)\s*=/i.test(t)) { // SQL Server / Azure SQL (ADO.NET style)
			const kv = {};
			t.split(';').forEach((p) => { const i = p.indexOf('='); if (i > 0) kv[p.slice(0, i).trim().toLowerCase()] = p.slice(i + 1).trim(); });
			const server = (kv.server || kv['data source'] || kv.address || '').replace(/^tcp:/i, '');
			const [host, port] = server.split(',');
			return { dbType: 'SQLSERVER', host: host.split('\\')[0], port: port, databaseName: kv['initial catalog'] || kv.database, username: kv['user id'] || kv.uid || kv.user,
				password: kv.password || kv.pwd, sslMode: /true|yes|mandatory|strict/i.test(kv.encrypt || '') ? (/true|yes/i.test(kv.trustservercertificate || '') ? 'REQUIRE' : 'VERIFY') : undefined };
		}
		return null;
	}

	function fillFromConnection() {
		const raw = form.elements.connString.value;
		if (!raw.trim()) { form.elements.connString.focus(); return; }
		const c = parseConnection(raw);
		if (!c) { toast("That connection string wasn't recognised. Fill in the fields below instead."); return; }
		const f = form.elements;
		f.dbType.value = c.dbType;
		syncDbType();
		['host', 'databaseName', 'username', 'jdbcUrl'].forEach((k) => { if (c[k] != null) f[k].value = c[k]; });
		f.port.value = c.port || '';
		if (c.password) f.password.value = c.password;
		if (c.sslMode) f.sslMode.value = c.sslMode;
		if (c.dbType !== 'CUSTOM') f.jdbcUrl.value = '';
		f.connString.value = '';
		toast(c.password ? 'Filled in. The password is stored encrypted when you save.' : 'Filled in. Add the password, then press Test.');
		(c.password ? $('testBtn') : f.password).focus();
	}

	const locationKey = () => [form.elements.queryText.value, form.elements.filePath.value, form.elements.sheet.value, form.elements.jdbcUrl.value, form.elements.host.value, form.elements.databaseName.value].join('|');

	function clearConfidence() { document.querySelectorAll('.src-conf').forEach((e) => e.remove()); }

	/** A small "auto · 92%" tag next to each column the detector filled in, with its reasoning on hover. */
	function showConfidence(det, suggested) {
		clearConfidence();
		if (!det) return;
		const found = Object.keys(det.confidence || {});
		$('autoBadge').hidden = !found.length;
		$('mapSub').textContent = found.length
			? `Recognised ${found.length} column${found.length === 1 ? '' : 's'} from their values (hover a tag to see why). Change anything that's wrong.`
			: 'No columns could be recognised from the values; choose them below.';
		found.forEach((role) => {
			const el = form.elements[role];
			if (!el || el.value !== suggested[role]) return;
			const c = det.confidence[role], tag = document.createElement('span');
			tag.className = 'src-conf' + (c >= 80 ? '' : c >= 60 ? ' mid' : ' low');
			tag.textContent = 'auto · ' + c + '%';
			tag.title = det.reasons[role] || '';
			el.closest('label').appendChild(tag);
		});
	}
	MAP_FIELDS.forEach((n) => form.elements[n].addEventListener('change', (e) => { const t = e.target.closest('label').querySelector('.src-conf'); if (t) t.remove(); }));

	// ---------------------------------------------------------------- find log tables
	async function discover() {
		const box = $('discover'), btn = $('discoverBtn');
		box.hidden = false;
		box.innerHTML = '<div class="src-discover-head"><span class="src-spin"></span>Looking through the database for tables that hold logs…</div>';
		btn.disabled = true;
		try {
			const r = await api('/api/sources/discover' + (st.editing ? '?id=' + st.editing.id : ''), { method: 'POST', body: JSON.stringify(payload()) });
			if (!r.ok) { box.innerHTML = `<div class="src-test-state bad">${esc(r.error)}</div>${r.hint ? `<div class="src-hint"><b>What to check:</b> ${esc(r.hint)}</div>` : ''}`; return; }
			if (!r.tables.length) {
				box.innerHTML = `<div class="src-discover-head">Connected to ${esc(r.product)}, but none of the ${nf.format(r.tablesChecked)} tables looked at hold logs. Type the table name or a SELECT query instead.</div>`;
				return;
			}
			box.innerHTML = `<div class="src-discover-head">Found ${r.tables.length} likely log table${r.tables.length === 1 ? '' : 's'} in ${esc(r.product)} (${nf.format(r.tablesInDatabase)} tables and views in the database). Pick one:</div>`
				+ r.tables.map((t, i) => `<div class="src-table-cand" data-i="${i}">
					<div class="src-score">${t.score}%<small>log-like</small></div>
					<div><b>${esc(t.table)}</b> <span class="src-type">${t.type.toUpperCase()}</span>
						<div class="src-roles">${Object.entries(t.suggested).map(([role, col]) => `<span>${ROLE_LABEL[role]}: <b>${esc(col)}</b></span>`).join('')}</div></div>
					<button type="button" class="btn small${i === 0 ? ' primary' : ''}" data-use="${i}">Use this table</button>
				</div>`).join('');
			box.querySelectorAll('[data-use]').forEach((b) => b.addEventListener('click', () => {
				const t = r.tables[Number(b.dataset.use)];
				box.querySelectorAll('.src-table-cand').forEach((c) => c.classList.toggle('chosen', c === b.closest('.src-table-cand')));
				form.elements.queryText.value = t.table;
				if (!form.elements.name.value) form.elements.name.value = t.table;
				test();
			}));
		} catch (e) { box.innerHTML = `<div class="src-test-state bad">${esc(e.message)}</div>`; }
		finally { btn.disabled = false; }
	}

	async function save() {
		const p = payload();
		try {
			$('saveBtn').disabled = true;
			const s = st.editing ? await api('/api/sources/' + st.editing.id, { method: 'PUT', body: JSON.stringify(p) }) : await api('/api/sources', { method: 'POST', body: JSON.stringify(p) });
			closeEditor();
			const auto = s.autoDetected ? Object.keys(s.autoDetected).length : 0;
			toast(auto ? `Saved. ${auto} columns recognised automatically; reading ${s.name}…` : `Saved. Reading ${s.name}…`);
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
	$('discoverBtn').addEventListener('click', discover);
	$('parseConnBtn').addEventListener('click', fillFromConnection);
	form.elements.connString.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); fillFromConnection(); } });
	form.elements.sheet.addEventListener('change', () => { if (form.elements.filePath.value.trim()) test(); });
	$('browseFileBtn').addEventListener('click', async () => {
		try {
			const r = await api('/api/desktop/browse?mode=file' + (form.elements.filePath.value ? '&start=' + encodeURIComponent(form.elements.filePath.value) : ''), { method: 'POST' });
			if (r.path) { form.elements.filePath.value = r.path; form.elements.name.value = form.elements.name.value || r.path.split(/[\\/]/).pop(); setOptions(form.elements.sheet, [], ''); test(); }
		} catch (e) { toast(e.message); }
	});
	$('sampleBtn').addEventListener('click', async () => {
		$('sampleBtn').disabled = true;
		try {
			const s = await api('/api/sources/sample', { method: 'POST' });
			toast(`Sample Siebel logs are being written to ${s.path}`);
			await refresh();
			setTimeout(() => { if (window.LA_SHELL) window.LA_SHELL.go('overview'); else location.href = '/'; }, 1200);
		} catch (e) { toast('Failed: ' + e.message); $('sampleBtn').disabled = false; }
	});

	if (DESKTOP) api('/api/desktop/info').then((i) => { st.info = i; }).catch(() => {});
	refresh();
	setInterval(() => { if (!document.hidden) refresh(); }, 3000);
})();
