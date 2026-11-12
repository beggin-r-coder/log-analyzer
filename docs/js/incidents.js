/* Log Analyzer – business impact, SLA/error budget, incident desk and alert rules. */
(function () {
	'use strict';
	const LA = window.LA;
	if (!LA) return;
	const $ = (id) => document.getElementById(id);
	const esc = LA.esc;
	const nf = LA.nf || new Intl.NumberFormat('en-US');
	const nfIn = new Intl.NumberFormat('en-IN');
	const POLL_MS = 10000;
	const isAdmin = !!(window.APP_USER && window.APP_USER.admin);

	const st = {
		filter: 'active',
		impact: null,
		list: null,
		known: null,      // ids of incidents seen so far, for "new incident" toasts
		panelId: null,
		panel: null,
		rules: null
	};

	// ---------- formatting ----------
	/** Indian-style money: ₹4.21 L, ₹1.30 Cr. */
	function inr(v) {
		if (v == null) return '—';
		if (v >= 1e7) return '₹' + (v / 1e7).toFixed(2) + ' Cr';
		if (v >= 1e5) return '₹' + (v / 1e5).toFixed(2) + ' L';
		return '₹' + nfIn.format(Math.round(v));
	}
	function dur(min) {
		if (min == null) return '—';
		if (min < 1) return Math.max(1, Math.round(min * 60)) + 's';
		if (min < 60) return Math.round(min) + 'm';
		const h = Math.floor(min / 60), m = Math.round(min % 60);
		if (h < 24) return h + 'h ' + String(m).padStart(2, '0') + 'm';
		return Math.floor(h / 24) + 'd ' + (h % 24) + 'h';
	}
	function when(ms) {
		if (!ms) return '—';
		const t = LA.fmtLogTime(ms);
		return (t.rel || t.full) + ', ' + t.time;
	}
	const incId = (i) => 'INC-' + String(i.id).padStart(4, '0');
	const STATUS_LABEL = { OPEN: 'Open', ACKNOWLEDGED: 'Acknowledged', RESOLVED: 'Resolved' };

	async function post(path, body) {
		return LA.api(path, {
			method: 'POST',
			headers: body ? { 'Content-Type': 'application/json' } : undefined,
			body: body ? JSON.stringify(body) : undefined
		});
	}

	// ==========================================================
	// BUSINESS IMPACT + SLA
	// ==========================================================
	async function refreshImpact() {
		try {
			st.impact = await LA.api('/api/impact?range=' + encodeURIComponent(LA.range));
		} catch (e) { return; }
		renderImpact();
		renderSla();
		renderIncStats();
	}

	function renderImpact() {
		const m = st.impact;
		if (!m) return;
		$('impactSub').textContent = 'Estimated customer and money impact · ' + (LA.RANGE_LABEL[m.range] || m.range).toLowerCase();
		$('impactHow').title = m.method;
		$('impactHeadline').textContent = m.headline;
		$('impValue').textContent = inr(m.valueAtRisk);
		$('impTxns').textContent = nf.format(m.failedTransactions);
		$('impCustomers').textContent = nf.format(m.customersAffected);
		$('impMttr').textContent = m.mttrMinutes == null ? '—' : dur(m.mttrMinutes);
		$('impMtta').textContent = 'MTTA ' + (m.mttaMinutes == null ? '—' : dur(m.mttaMinutes));
		const max = Math.max(1, ...m.byService.map((s) => s.valueAtRisk));
		$('impactServices').innerHTML = m.byService.length ? `<div class="imp-svc-head"><span>Where it failed</span><span>value · txns</span></div>` + m.byService.slice(0, 5).map((s) => `
			<button class="imp-svc" data-service="${esc(s.service)}" title="Show failed events of ${esc(s.service)}">
				<span class="name mono">${esc(s.service)}</span>
				<span class="bar"><i style="width:${(s.valueAtRisk / max * 100).toFixed(1)}%"></i></span>
				<span class="val">${inr(s.valueAtRisk)} · ${nf.format(s.failedTransactions)}</span>
			</button>`).join('') : '<div class="empty" style="padding:14px 0">No failed payments in this period.</div>';
	}

	function renderSla() {
		const m = st.impact;
		if (!m) return;
		$('slaChips').innerHTML = [
			m.slaBreached ? `<span class="chip chip-alert">${m.slaBreached} breached</span>` : '',
			m.slaAtRisk ? `<span class="chip chip-warn">${m.slaAtRisk} at risk</span>` : '',
			`<span class="chip">${m.slaMet} met</span>`].join('');
		const label = { MET: 'Met', AT_RISK: 'At risk', BREACHED: 'Breached' };
		document.querySelector('#slaTable tbody').innerHTML = m.sla.map((s) => {
			const used = Math.min(100, s.budgetUsed);
			return `<tr class="log-row" data-service="${esc(s.service)}" title="Down ${s.downMinutes} min of an allowed ${s.budgetMinutes} min · owner ${esc(s.owner)}">
				<td><div class="mono" style="font-size:12.5px;font-weight:600">${esc(s.service)}</div><div class="sla-owner">${esc(s.owner)}</div></td>
				<td class="num-cell hide-xs">${s.target}%</td>
				<td class="num-cell">${s.availability.toFixed(s.availability >= 99.9 ? 3 : 2)}%</td>
				<td><div class="budget"><div class="budget-bar st-${s.status}"><i style="width:${used}%"></i></div><span class="num-cell">${s.budgetUsed >= 1000 ? '>999' : Math.round(s.budgetUsed)}%</span></div></td>
				<td><span class="sla-pill st-${s.status}">${label[s.status]}</span></td>
			</tr>`;
		}).join('') || '<tr><td colspan="5" class="empty">No services reporting.</td></tr>';
	}

	// ==========================================================
	// INCIDENT DESK
	// ==========================================================
	async function refreshIncidents() {
		try {
			st.list = await LA.api(`/api/incidents?range=${encodeURIComponent(LA.range === '15m' || LA.range === '1h' || LA.range === '6h' ? '24h' : LA.range)}&status=${st.filter}`);
		} catch (e) { return; }
		renderIncidents();
		renderIncStats();
	}

	function renderIncStats() {
		const l = st.list, m = st.impact;
		if (l) {
			$('incOpen').textContent = l.open;
			$('incAcked').textContent = l.acknowledged;
			$('incResolved').textContent = l.resolved;
			const active = l.open + l.acknowledged;
			const badge = $('incNavBadge');
			badge.hidden = active === 0;
			badge.textContent = active;
			badge.classList.toggle('hot', l.open > 0);
			$('kpiIncidents').textContent = active;
			$('incidentsMeter').style.width = Math.min(100, active * 34) + '%';
			$('incidentsTop').textContent = l.open ? l.open + ' unacknowledged' : active ? 'all acknowledged' : 'none';
		}
		if (m) {
			$('incMtta').textContent = dur(m.mttaMinutes);
			$('incMttr').textContent = dur(m.mttrMinutes);
			$('incidentsNote').textContent = m.mttrMinutes == null ? 'from alert rules' : 'MTTR ' + dur(m.mttrMinutes);
		}
	}

	function actionButtons(i) {
		if (i.status === 'OPEN') return `<button class="btn small" data-act="ack" data-id="${i.id}">Acknowledge</button><button class="btn small primary" data-act="resolve" data-id="${i.id}">Resolve</button>`;
		if (i.status === 'ACKNOWLEDGED') return `<button class="btn small primary" data-act="resolve" data-id="${i.id}">Resolve</button>`;
		return '';
	}

	function renderIncidents() {
		const items = st.list.items;
		if (!items.length) {
			$('incList').innerHTML = `<div class="empty">${st.filter === 'active' ? 'No active incidents. Every service is within its alert thresholds.' : 'No incidents in this period.'}</div>`;
			return;
		}
		$('incList').innerHTML = items.map((i) => {
			const live = i.status !== 'RESOLVED';
			const timing = live
				? `opened ${LA.ago(i.openedAt)} · ongoing ${dur(i.durationMinutes)}`
				: `${when(i.openedAt)} · lasted ${dur(i.durationMinutes)}`;
			return `<div class="inc-row sev-${esc(i.severity)} st-${i.status}" data-id="${i.id}" tabindex="0" role="button" aria-label="Open ${incId(i)}">
				<span class="sev">${esc(i.severity)}</span>
				<div class="inc-main">
					<div class="inc-title"><span class="inc-id mono">${incId(i)}</span>${esc(i.title)}</div>
					<div class="inc-meta"><span class="mono">${esc(i.service)}</span> · ${esc(i.owner)} · ${timing} · peak ${i.peakErrorRate}% errors</div>
				</div>
				<span class="inc-status st-${i.status}">${STATUS_LABEL[i.status]}${i.status === 'ACKNOWLEDGED' && i.acknowledgedBy ? ' · ' + esc(i.acknowledgedBy) : ''}</span>
				<div class="inc-btns">${actionButtons(i)}</div>
			</div>`;
		}).join('');
	}

	async function act(kind, id, note) {
		try {
			const d = kind === 'ack' ? await post(`/api/incidents/${id}/ack`) : await post(`/api/incidents/${id}/resolve`, { note: note || '' });
			LA.toast(`${incId(d)} ${kind === 'ack' ? 'acknowledged' : 'resolved'}`);
			if (st.panelId === id) { st.panel = d; renderPanel(); }
			refreshIncidents();
			refreshImpact();
		} catch (e) { LA.toast('Failed: ' + e.message); }
	}

	// ==========================================================
	// INCIDENT PANEL
	// ==========================================================
	async function openPanel(id) {
		st.panelId = id;
		st.panel = null;
		closeRules();
		document.body.classList.add('inc-open');
		$('incPanel').setAttribute('aria-hidden', 'false');
		$('incPanelTitle').textContent = 'Loading…';
		$('incPanelSub').textContent = '';
		$('incPanelBody').innerHTML = '<div class="empty">Loading incident…</div>';
		try {
			const d = await LA.api('/api/incidents/' + id);
			if (st.panelId !== id) return;
			st.panel = d;
			renderPanel();
		} catch (e) { $('incPanelBody').innerHTML = `<div class="empty">Could not load: ${esc(e.message)}</div>`; }
	}

	function closePanel() {
		document.body.classList.remove('inc-open');
		$('incPanel').setAttribute('aria-hidden', 'true');
		st.panelId = null;
	}

	const EVENT_ICON = { OPENED: '!', ACKNOWLEDGED: '✓', NOTE: '✎', UPDATED: '↑', RESOLVED: '✓', RECOVERED: '✓' };

	function renderPanel() {
		const d = st.panel;
		if (!d) return;
		$('incPanelTitle').textContent = d.title;
		$('incPanelSub').innerHTML = `<span class="mono">${incId(d)}</span> · <span class="sev sev-${esc(d.severity)}">${esc(d.severity)}</span> <span class="inc-status st-${d.status}">${STATUS_LABEL[d.status]}</span>`;
		const imp = d.impact;
		$('incPanelBody').innerHTML = `
			<div class="panel-sec">
				<div class="inc-facts">
					<div><span>Service</span><b class="mono">${esc(d.service)}</b></div>
					<div><span>Owner</span><b>${esc(d.owner)} on-call</b></div>
					<div><span>Opened</span><b>${when(d.openedAt)}</b></div>
					<div><span>${d.status === 'RESOLVED' ? 'Duration' : 'Ongoing for'}</span><b>${dur(d.durationMinutes)}</b></div>
					<div><span>Acknowledged</span><b>${d.acknowledgedAt ? dur((d.acknowledgedAt - d.openedAt) / 60000) + ' after opening · ' + esc(d.acknowledgedBy) : 'not yet'}</b></div>
					<div><span>Resolved</span><b>${d.resolvedAt ? when(d.resolvedAt) + ' · ' + esc(d.resolvedBy) : 'not yet'}</b></div>
					<div><span>Alert rule</span><b>${esc(d.rule || '—')}</b></div>
					<div><span>Peak error rate</span><b>${d.peakErrorRate}%</b></div>
				</div>
			</div>
			${imp ? `<div class="panel-sec">
				<div class="sec-title">Business impact during the incident</div>
				<div class="ai-stats" style="grid-template-columns:repeat(3,1fr);margin:0">
					<div><b>${inr(imp.valueAtRisk)}</b><span>value at risk</span></div>
					<div><b>${nf.format(imp.failedTransactions)}</b><span>failed transactions</span></div>
					<div><b>${nf.format(imp.customersAffected)}</b><span>customers affected</span></div>
				</div>
			</div>` : ''}
			<div class="panel-sec">
				<div class="sec-title">Timeline</div>
				<ol class="timeline">${d.timeline.map((e) => `
					<li class="ev-${e.type}">
						<i>${EVENT_ICON[e.type] || '•'}</i>
						<div><div class="ev-head"><b>${esc(e.type.charAt(0) + e.type.slice(1).toLowerCase())}</b> · ${esc(e.actor)} <span class="ai-muted">${when(e.at)}</span></div>
						<div class="ev-text">${esc(e.text).replace(/\n/g, '<br>')}</div></div>
					</li>`).join('')}
				</ol>
			</div>
			<div class="panel-sec">
				<div class="sec-title">${d.status === 'RESOLVED' ? 'Add a note' : 'Update'}</div>
				<textarea id="incNote" class="inc-note" rows="3" placeholder="${d.status === 'RESOLVED' ? 'Follow-up, root cause, ticket link…' : 'What did you find or do? Notes are added to the timeline.'}"></textarea>
				<div class="inc-panel-btns">
					<button class="btn small" id="incAddNote">Add note</button>
					${d.status === 'OPEN' ? '<button class="btn small" id="incAck">Acknowledge</button>' : ''}
					${d.status !== 'RESOLVED' ? '<button class="btn small primary" id="incResolve">Resolve with note</button>' : ''}
					<span style="flex:1"></span>
					<button class="btn ghost small" id="incLogs">View logs</button>
					<button class="btn ghost small" id="incAnalyze">Analyze errors</button>
				</div>
			</div>`;
	}

	function summaryMarkdown(d) {
		const lines = [
			`## ${incId(d)}: ${d.title}`, '',
			`- **Severity:** ${d.severity} · **Status:** ${STATUS_LABEL[d.status]}`,
			`- **Service:** ${d.service} (owner: ${d.owner})`,
			`- **Opened:** ${new Date(d.openedAt).toLocaleString()}`,
			`- **Acknowledged:** ${d.acknowledgedAt ? new Date(d.acknowledgedAt).toLocaleString() + ' by ' + d.acknowledgedBy : '—'}`,
			`- **Resolved:** ${d.resolvedAt ? new Date(d.resolvedAt).toLocaleString() + ' by ' + d.resolvedBy : '—'}`,
			`- **Duration:** ${dur(d.durationMinutes)} · **Peak error rate:** ${d.peakErrorRate}%`
		];
		if (d.impact) lines.push(`- **Impact (est.):** ${inr(d.impact.valueAtRisk)} across ${d.impact.failedTransactions} failed transactions, ~${d.impact.customersAffected} customers`);
		lines.push('', '### Timeline');
		d.timeline.forEach((e) => lines.push(`- ${new Date(e.at).toLocaleTimeString()} **${e.type}** (${e.actor}): ${e.text.replace(/\n/g, ' ')}`));
		return lines.join('\n');
	}

	async function latestErrorOf(service, from, to) {
		const p = new URLSearchParams({ service, levels: 'ERROR,FATAL', sort: 'timestamp', dir: 'desc', page: 0, size: 1, range: '7d' });
		const page = await LA.api('/api/logs?' + p);
		return page.content.find((r) => !to || (r.timestamp >= from && r.timestamp <= to)) || page.content[0];
	}

	// ==========================================================
	// ALERT RULES
	// ==========================================================
	async function openRules() {
		closePanel();
		document.body.classList.add('rules-open');
		$('rulesPanel').setAttribute('aria-hidden', 'false');
		$('rulesBody').innerHTML = '<div class="empty">Loading rules…</div>';
		try { st.rules = await LA.api('/api/alert-rules'); } catch (e) { $('rulesBody').innerHTML = `<div class="empty">${esc(e.message)}</div>`; return; }
		renderRules();
	}

	function closeRules() {
		document.body.classList.remove('rules-open');
		$('rulesPanel').setAttribute('aria-hidden', 'true');
	}

	function condition(r) {
		return r.metric === 'ERROR_COUNT'
			? `≥ ${r.threshold} errors in ${r.windowMinutes} min`
			: `error rate ≥ ${r.threshold}% over ${r.windowMinutes} min (min ${r.minErrors} errors)`;
	}

	function renderRules() {
		const sev = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
		const ro = !isAdmin;
		$('rulesBody').innerHTML = `
			${ro ? '<p class="rules-note">You can view the rules. Only an administrator can change them.</p>' : '<p class="rules-note">Changes apply on the next evaluation, within 20 seconds.</p>'}
			<div class="rules">${st.rules.map((r) => `
				<div class="rule${r.enabled ? '' : ' off'}" data-id="${r.id}">
					<div class="rule-top">
						<div><div class="rule-name">${esc(r.name)}</div><div class="rule-cond"><span class="mono">${esc(r.service === '*' ? 'any service' : r.service)}</span> · ${esc(condition(r))}</div></div>
						<span class="sev sev-${esc(r.severity)}">${esc(r.severity)}</span>
					</div>
					${ro ? '' : `<div class="rule-edit">
						<label>Threshold <input type="number" min="0.1" step="0.1" data-k="threshold" value="${r.threshold}"></label>
						<label>Window (min) <input type="number" min="1" max="120" data-k="windowMinutes" value="${r.windowMinutes}"></label>
						<label>Severity <select data-k="severity">${sev.map((s) => `<option${s === r.severity ? ' selected' : ''}>${s}</option>`).join('')}</select></label>
						<label class="rule-en"><input type="checkbox" data-k="enabled" ${r.enabled ? 'checked' : ''}> Enabled</label>
						<span class="rule-btns"><button class="btn ghost small" data-rule="delete">Delete</button><button class="btn small primary" data-rule="save">Save</button></span>
					</div>`}
				</div>`).join('')}
			</div>
			${ro ? '' : `<div class="rule new-rule">
				<div class="rule-name">New rule</div>
				<div class="rule-edit">
					<label>Name <input type="text" data-k="name" placeholder="e.g. Loan API errors"></label>
					<label>Service <input type="text" data-k="service" placeholder="* for all" value="*"></label>
					<label>Metric <select data-k="metric"><option value="ERROR_RATE">Error rate %</option><option value="ERROR_COUNT">Error count</option></select></label>
					<label>Threshold <input type="number" min="0.1" step="0.1" data-k="threshold" value="10"></label>
					<label>Window (min) <input type="number" min="1" max="120" data-k="windowMinutes" value="5"></label>
					<label>Min errors <input type="number" min="0" data-k="minErrors" value="5"></label>
					<label>Severity <select data-k="severity">${sev.map((s) => `<option${s === 'HIGH' ? ' selected' : ''}>${s}</option>`).join('')}</select></label>
					<span class="rule-btns"><button class="btn small primary" data-rule="create">Add rule</button></span>
				</div>
			</div>`}`;
	}

	function readRule(box, base) {
		const r = Object.assign({}, base || { enabled: true, minErrors: 0 });
		box.querySelectorAll('[data-k]').forEach((el) => {
			const k = el.dataset.k;
			r[k] = el.type === 'checkbox' ? el.checked : el.type === 'number' ? Number(el.value) : el.value;
		});
		return r;
	}

	async function ruleAction(btn) {
		const box = btn.closest('.rule');
		const kind = btn.dataset.rule;
		try {
			if (kind === 'create') {
				await LA.api('/api/alert-rules', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(readRule(box)) });
				LA.toast('Alert rule added');
			} else {
				const id = Number(box.dataset.id), base = st.rules.find((r) => r.id === id);
				if (kind === 'delete') {
					if (!confirm(`Delete the rule “${base.name}”?`)) return;
					const res = await fetch('/api/alert-rules/' + id, { method: 'DELETE' });
					if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
					LA.toast('Alert rule deleted');
				} else {
					await LA.api('/api/alert-rules/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(readRule(box, base)) });
					LA.toast('Alert rule saved');
				}
			}
			st.rules = await LA.api('/api/alert-rules');
			renderRules();
		} catch (e) { LA.toast('Failed: ' + e.message); }
	}

	// ==========================================================
	// EVENTS
	// ==========================================================
	function init() {
		$('incFilter').addEventListener('click', (ev) => {
			const b = ev.target.closest('[data-f]');
			if (!b) return;
			st.filter = b.dataset.f;
			$('incFilter').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
			refreshIncidents();
		});
		$('incList').addEventListener('click', (ev) => {
			const b = ev.target.closest('[data-act]');
			if (b) { ev.stopPropagation(); act(b.dataset.act, Number(b.dataset.id)); return; }
			const row = ev.target.closest('.inc-row');
			if (row) openPanel(Number(row.dataset.id));
		});
		$('incList').addEventListener('keydown', (ev) => {
			const row = ev.target.closest('.inc-row');
			if (row && (ev.key === 'Enter' || ev.key === ' ') && ev.target === row) { ev.preventDefault(); openPanel(Number(row.dataset.id)); }
		});
		$('incRulesBtn').addEventListener('click', openRules);
		$('rulesClose').addEventListener('click', closeRules);
		$('rulesBody').addEventListener('click', (ev) => { const b = ev.target.closest('[data-rule]'); if (b) ruleAction(b); });
		$('incPanelClose').addEventListener('click', closePanel);
		$('incPanelBackdrop').addEventListener('click', () => { closePanel(); closeRules(); });
		document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape') { closePanel(); closeRules(); } });
		$('incCopy').addEventListener('click', async () => {
			if (!st.panel) return;
			try { await navigator.clipboard.writeText(summaryMarkdown(st.panel)); LA.toast('Incident summary copied as Markdown'); } catch (e) { LA.toast('Copy failed'); }
		});
		$('incPanelBody').addEventListener('click', async (ev) => {
			const d = st.panel;
			if (!d) return;
			const id = ev.target.id;
			const note = $('incNote') ? $('incNote').value.trim() : '';
			if (id === 'incAck') act('ack', d.id);
			if (id === 'incResolve') act('resolve', d.id, note);
			if (id === 'incAddNote') {
				if (!note) { LA.toast('Write a note first'); return; }
				try { st.panel = await post(`/api/incidents/${d.id}/notes`, { text: note }); renderPanel(); LA.toast('Note added'); } catch (e) { LA.toast('Failed: ' + e.message); }
			}
			if (id === 'incLogs') {
				closePanel();
				LA.showWidget('explorer');
				LA.filterExplorer({ service: d.service, levels: ['ERROR', 'FATAL'] });
			}
			if (id === 'incAnalyze') {
				try {
					const r = await latestErrorOf(d.service, d.windowFrom, d.windowTo);
					if (!r) { LA.toast('No errors found for ' + d.service); return; }
					closePanel();
					const t = document.createElement('button');
					t.hidden = true;
					t.dataset.analyze = r.id;
					document.body.appendChild(t);
					t.click(); // opens the single-error analysis panel (ai.js)
					t.remove();
				} catch (e) { LA.toast('Failed: ' + e.message); }
			}
		});
		$('impactServices').addEventListener('click', (ev) => {
			const b = ev.target.closest('[data-service]');
			if (!b) return;
			LA.showWidget('explorer');
			LA.filterExplorer({ service: b.dataset.service, levels: ['ERROR', 'FATAL'] });
		});
		document.querySelector('#slaTable tbody').addEventListener('click', (ev) => {
			const tr = ev.target.closest('[data-service]');
			if (!tr) return;
			LA.showWidget('explorer');
			LA.filterExplorer({ service: tr.dataset.service, levels: ['ERROR', 'FATAL'] });
		});
		$('impactHow').addEventListener('click', () => { if (st.impact) LA.toast(st.impact.method); });

		document.addEventListener('la:range', () => { refreshImpact(); refreshIncidents(); });
		let pending;
		document.addEventListener('la:incident', (ev) => {
			const i = ev.detail;
			if (i && i.change === 'opened') {
				LA.toast(`New ${i.severity} incident ${incId(i)}: ${i.title} · paged ${i.owner}`);
			}
			if (st.panelId && i && i.id === st.panelId && i.change !== 'note') openPanel(i.id);
			clearTimeout(pending);
			pending = setTimeout(() => { refreshIncidents(); refreshImpact(); }, 300);
		});
		document.addEventListener('la:open-latest-incident', async () => {
			await refreshIncidents();
			const first = st.list && st.list.items[0];
			if (first) openPanel(first.id);
			else LA.toast('No active incident yet. Simulate one, then give the alert rules about a minute.');
		});

		refreshImpact();
		refreshIncidents();
		setInterval(() => { if (!document.hidden) { refreshIncidents(); } }, POLL_MS);
		setInterval(() => { if (!document.hidden) { refreshImpact(); } }, POLL_MS * 3);
	}

	// shared with report.js (PDF export)
	LA.business = {
		get impact() { return st.impact; },
		get incidents() { return st.list; },
		inr, dur, incId,
		/** Fresh data for the PDF, independent of the desk's current filter. */
		async forReport() {
			const [impact, list] = await Promise.all([
				LA.api('/api/impact?range=' + encodeURIComponent(LA.range)),
				LA.api(`/api/incidents?range=${encodeURIComponent(LA.range === '15m' || LA.range === '1h' || LA.range === '6h' ? '24h' : LA.range)}&status=all`)
			]);
			return { impact, list };
		}
	};

	init();
})();
