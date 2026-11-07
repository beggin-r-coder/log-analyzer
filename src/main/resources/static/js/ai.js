/* AI Log Analyzer – AI error analysis (Gemini + local NLP).
 * Dashboard widget: analysis of all errors in the selected range.
 * Side panel: analysis of one log event with its NLP context. */
(function () {
	'use strict';

	const LA = window.LA;
	const $ = (id) => document.getElementById(id);
	const esc = LA.esc;
	const nf = new Intl.NumberFormat('en-US');
	const SEV_CLASS = { CRITICAL: 'sev-CRITICAL', HIGH: 'sev-HIGH', MEDIUM: 'sev-MEDIUM', LOW: 'sev-LOW' };

	const ai = { status: null, overview: null, running: false, panelId: null, panelInsight: null, panelContext: null, panelRecord: null };

	// ==========================================================
	// SHARED RENDERING
	// ==========================================================
	function list(items, ordered, checkable) {
		if (!items || !items.length) return '<p class="ai-muted">None.</p>';
		const tag = ordered ? 'ol' : 'ul';
		return `<${tag} class="ai-list${checkable ? ' steps' : ''}">` + items.map((x, i) => checkable
			? `<li><label><input type="checkbox" aria-label="Mark step ${i + 1} done"><span>${esc(x)}</span></label></li>`
			: `<li>${esc(x)}</li>`).join('') + `</${tag}>`;
	}

	function meta(a) {
		const bits = [];
		bits.push(a.source === 'GEMINI' ? `Gemini · ${esc(a.model)}` : 'Offline NLP playbook');
		if (a.latencyMs) bits.push((a.latencyMs / 1000).toFixed(1) + 's');
		const tokens = a.usage && (a.usage.totalTokenCount || a.usage.total_token_count);
		if (tokens) bits.push(nf.format(tokens) + ' tokens');
		if (a.source === 'GEMINI') bits.push(a.redactions ? `${a.redactions} personal data item${a.redactions > 1 ? 's' : ''} redacted before sending` : 'no personal data found to redact');
		bits.push((a.cached ? 'saved analysis from ' : 'analysed ') + LA.ago(a.generatedAt));
		return bits.join(' · ');
	}

	function header(a) {
		const conf = a.confidence == null ? '' : `
			<div class="ai-conf" title="How confident the analysis is in the root cause">
				<span>Confidence</span><div class="ai-conf-bar"><i style="width:${Math.max(0, Math.min(100, a.confidence))}%"></i></div><b>${a.confidence}%</b>
			</div>`;
		return `<div class="ai-head">
			<span class="sev ${SEV_CLASS[a.severity] || 'sev-MEDIUM'}">${esc(a.severity || 'MEDIUM')}</span>
			${conf}
			<span class="ai-meta">${meta(a)}</span>
		</div>
		${a.note ? `<div class="ai-note">${esc(a.note)}</div>` : ''}`;
	}

	function body(a, compact) {
		return `<div class="ai-grid${compact ? ' compact' : ''}">
			<div class="ai-col">
				<h4>Summary</h4><p>${esc(a.summary || '—')}</p>
				<h4>Probable root cause</h4><p>${esc(a.rootCause || '—')}</p>
				<h4>Impact</h4><p>${esc(a.impact || '—')}</p>
				${a.evidence && a.evidence.length ? `<h4>Evidence</h4>${list(a.evidence)}` : ''}
			</div>
			<div class="ai-col">
				<h4>Recovery steps</h4>${list(a.recoverySteps, true, true)}
				<h4>Prevention</h4>${list(a.preventiveActions)}
			</div>
		</div>`;
	}

	function markdown(a, title, extra) {
		const lines = [`## ${title}`, ''];
		if (extra) lines.push(...extra, '');
		lines.push(`**Severity:** ${a.severity || '-'}${a.confidence != null ? ` (confidence ${a.confidence}%)` : ''}`, '');
		lines.push('### Summary', a.summary || '-', '', '### Probable root cause', a.rootCause || '-', '', '### Impact', a.impact || '-', '');
		lines.push('### Recovery steps', ...(a.recoverySteps || []).map((x, i) => `${i + 1}. ${x}`), '');
		lines.push('### Prevention', ...(a.preventiveActions || []).map((x) => `- ${x}`), '');
		if (a.evidence && a.evidence.length) lines.push('### Evidence', ...a.evidence.map((x) => `- ${x}`), '');
		lines.push(`_${a.source === 'GEMINI' ? 'Analysed by Gemini (' + a.model + ')' : 'Offline NLP analysis'} on ${new Date(a.generatedAt).toLocaleString()}_`);
		return lines.join('\n');
	}

	async function copy(text) {
		try {
			await navigator.clipboard.writeText(text);
		} catch (e) {
			const ta = document.createElement('textarea');
			ta.value = text;
			document.body.appendChild(ta);
			ta.select();
			document.execCommand('copy');
			ta.remove();
		}
		LA.toast('Copied to clipboard');
	}

	async function post(url) {
		const res = await fetch(url, { method: 'POST' });
		if (!res.ok) {
			let msg = res.status + ' ' + res.statusText;
			try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
			throw new Error(msg);
		}
		return res.json();
	}

	async function getOrNull(url) {
		const res = await fetch(url);
		if (res.status === 204) return null;
		if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
		return res.json();
	}

	// ==========================================================
	// DASHBOARD WIDGET – whole range
	// ==========================================================
	function renderStatus() {
		const s = ai.status;
		if (!s) return;
		const el = $('aiStatus');
		el.innerHTML = s.configured
			? `<span class="dot" style="background:#16a34a"></span>Gemini · ${esc(s.model)}`
			: `<span class="dot" style="background:#d97706"></span>Offline NLP mode`;
		el.title = s.configured ? 'Gemini is configured. Personal data is redacted before sending.' : 'Set GEMINI_API_KEY to enable Gemini. The local NLP playbook is used meanwhile.';
		$('aiRunText').textContent = ai.overview ? 'Re-run analysis' : s.configured ? 'Analyze errors' : 'Run offline analysis';
	}

	function renderOverview() {
		const a = ai.overview, box = $('aiBody');
		$('aiCopy').hidden = !a;
		renderStatus();
		if (!a) {
			box.innerHTML = `<div class="ai-empty"><div class="card-title">No analysis yet for ${esc((LA.RANGE_LABEL[LA.range] || LA.range).toLowerCase())}</div>
				<p>Click <b>${esc($('aiRunText').textContent)}</b> to get a summary, the likely root cause and step-by-step recovery guidance. To analyze one specific error, open it in the Log Explorer or Live Stream and click <b>Analyze</b>.</p></div>`;
			return;
		}
		const n = a.nlp || {};
		const clusters = n.clusters || [];
		const incidents = (a.incidents || []).map((inc) => {
			const c = clusters.find((x) => x.rank === inc.pattern);
			return `<div class="ai-incident ${SEV_CLASS[inc.severity] || ''}">
				<div class="ai-incident-top"><span class="sev">${esc(inc.severity)}</span>${inc.pattern ? `<span class="ai-muted">Pattern #${inc.pattern}${c ? ' · ' + nf.format(c.count) + '×' : ''}</span>` : ''}</div>
				<div class="ai-incident-title">${esc(inc.title)}</div>
				<p>${esc(inc.rootCause)}</p>
				<p class="ai-fix"><b>Do first:</b> ${esc(inc.recovery)}</p>
				${c && c.sampleId ? `<button class="link" data-analyze="${c.sampleId}">Analyze a sample →</button>` : ''}
			</div>`;
		}).join('');
		box.innerHTML = header(a) + body(a, false)
			+ (incidents ? `<h4 class="ai-section">Incidents</h4><div class="ai-incidents">${incidents}</div>` : '')
			+ `<details class="ai-nlp">
				<summary><b>NLP:</b> grouped ${nf.format(n.errorsAnalysed || 0)} errors into ${nf.format(n.patterns || 0)} patterns
					${(n.keywords || []).slice(0, 8).map((k) => `<span class="kw">${esc(k)}</span>`).join('')}</summary>
				<table class="ai-patterns"><thead><tr><th>#</th><th>Count</th><th>Services</th><th>Pattern</th><th>Last seen</th><th></th></tr></thead><tbody>
				${clusters.map((c) => `<tr>
					<td class="ai-muted">${c.rank}</td><td class="num-cell">${nf.format(c.count)}</td>
					<td>${esc((c.services || []).join(', '))}</td><td class="mono ai-pattern">${esc(c.template)}</td>
					<td class="ai-muted" style="white-space:nowrap">${esc(LA.agoShort(c.lastSeen))}</td>
					<td>${c.sampleId ? `<button class="link" data-analyze="${c.sampleId}">Analyze</button>` : ''}</td></tr>`).join('')}
				</tbody></table>
			</details>`;
	}

	async function runOverview(force) {
		if (ai.running) return;
		ai.running = true;
		$('aiRun').classList.add('busy');
		const label = $('aiRunText').textContent;
		$('aiRunText').textContent = 'Analyzing…';
		const errors = LA.summary ? LA.summary.totals.error + LA.summary.totals.fatal : null;
		$('aiBody').innerHTML = `<div class="ai-loading"><div class="spinner"></div>
			<div><b>${ai.status && ai.status.configured ? 'Asking Gemini' : 'Running the offline NLP playbook'}</b>
			<div class="ai-muted">Grouping ${errors != null ? nf.format(errors) + ' ' : ''}errors into patterns, redacting personal data, then analyzing. This usually takes 5–20 seconds.</div></div></div>`;
		try {
			ai.overview = await post(`/api/ai/overview?range=${encodeURIComponent(LA.range)}&force=${force ? 'true' : 'false'}`);
		} catch (e) {
			LA.toast('Analysis failed: ' + e.message);
			$('aiRunText').textContent = label;
		} finally {
			ai.running = false;
			$('aiRun').classList.remove('busy');
			renderOverview();
		}
	}

	async function loadLatestOverview() {
		try {
			ai.overview = await getOrNull(`/api/ai/overview/latest?range=${encodeURIComponent(LA.range)}`);
		} catch (e) {
			ai.overview = null;
		}
		renderOverview();
	}

	// ==========================================================
	// SIDE PANEL – one log event
	// ==========================================================
	function hourlyBars(hourly) {
		const max = Math.max(1, ...hourly);
		const w = 8, gap = 3, h = 34;
		const bars = hourly.map((v, i) => {
			const bh = v ? Math.max(2, v / max * h) : 1;
			return `<rect x="${i * (w + gap)}" y="${h - bh}" width="${w}" height="${bh}" rx="1.5" fill="${v ? (i === hourly.length - 1 ? '#e11d48' : '#fb7185') : '#e5e7eb'}"><title>${hourly.length - 1 - i}h ago: ${v}</title></rect>`;
		}).join('');
		return `<svg class="ai-hourly" viewBox="0 0 ${hourly.length * (w + gap) - gap} ${h}" preserveAspectRatio="none" role="img" aria-label="Occurrences per hour over the last 24 hours">${bars}</svg>
			<div class="ai-hourly-axis"><span>24h ago</span><span>now</span></div>`;
	}

	function entityChips(entities) {
		const labels = { exception: 'Exception', errorCode: 'Error code', transaction: 'Transaction', reference: 'Reference', account: 'Account', card: 'Card', vpa: 'UPI ID', duration: 'Duration', amount: 'Amount', table: 'Table' };
		const keys = Object.keys(entities || {});
		if (!keys.length) return '<p class="ai-muted">No entities found.</p>';
		return keys.map((k) => `<div class="ent-row"><span class="ent-k">${esc(labels[k] || k)}</span>${entities[k].map((v) => `<button class="ent" data-q="${esc(v)}" title="Search the explorer for this">${esc(v)}</button>`).join('')}</div>`).join('');
	}

	function logLine(r, clickable) {
		const t = LA.fmtLogTime(r.timestamp);
		return `<div class="ai-logline${clickable ? ' clickable' : ''}" ${clickable ? `data-open="${r.id}"` : ''}>
			<span class="lvl L-${esc(r.level)}">${esc(r.level)}</span>
			<span class="ai-muted">${t.rel ? esc(t.rel) + ' ' : ''}${esc(t.time)}</span>
			<span class="svc">${esc(r.service)}</span>
			<span class="mono msg">${esc(r.message)}</span>
			${r.score != null ? `<span class="score" title="${r.samePattern ? 'Same pattern' : 'Text similarity'}">${r.score}%</span>` : ''}
		</div>`;
	}

	function renderPanel() {
		const r = ai.panelRecord, c = ai.panelContext, a = ai.panelInsight;
		const bodyEl = $('aiPanelBody');
		if (!r) { bodyEl.innerHTML = '<div class="ai-loading"><div class="spinner"></div>Loading…</div>'; return; }
		const t = LA.fmtLogTime(r.timestamp);
		$('aiPanelSub').textContent = `${r.service} · ${t.rel ? t.rel + ', ' : ''}${t.full} ${t.time}`;

		let html = `<section class="panel-sec">
			<div class="ai-event-top"><span class="lvl L-${esc(r.level)}">${esc(r.level)}</span><b>${esc(r.service)}</b>${r.category ? `<span class="chip">${esc(r.category)}</span>` : ''}<span class="ai-muted">#${r.id}</span></div>
			<p class="mono ai-event-msg">${esc(r.message)}</p>
			<details><summary class="ai-muted">Raw line · ${esc(r.formatLabel || r.format || '')}</summary><pre class="raw">${esc(r.rawLine)}</pre></details>
		</section>`;

		// AI block
		const configured = ai.status && ai.status.configured;
		html += `<section class="panel-sec ai-block"><div class="sec-title"><span>AI analysis</span>
			<button class="btn small ${a ? 'ghost' : 'dark'}" id="aiPanelRun">${a ? 'Re-run' : configured ? '✦ Analyze with Gemini' : '✦ Run offline analysis'}</button></div>`;
		if (ai.panelLoading) {
			html += `<div class="ai-loading"><div class="spinner"></div><div><b>${configured ? 'Asking Gemini' : 'Running the offline playbook'}</b><div class="ai-muted">Sending this event with its trace, nearby errors and similar errors (personal data redacted).</div></div></div>`;
		} else if (a) {
			html += header(a) + body(a, true);
		} else {
			html += `<p class="ai-muted">Get a summary, the probable root cause and recovery steps for this event. ${configured ? 'Uses Gemini; personal data is redacted first.' : 'Gemini is not configured, so the local NLP playbook will be used.'}</p>`;
		}
		html += `</section>`;

		// NLP context
		if (!c) {
			html += '<section class="panel-sec"><div class="ai-loading"><div class="spinner"></div>Running NLP…</div></section>';
		} else {
			html += `<section class="panel-sec"><div class="sec-title"><span>Pattern (NLP)</span></div>
				<code class="ai-template">${esc(c.template)}</code>
				<div class="ai-stats">
					<div><b>${nf.format(c.occurrences24h || 0)}×</b><span>in the last 24h</span></div>
					<div><b>${c.firstSeen ? esc(LA.agoShort(c.firstSeen)) : '—'}</b><span>first seen</span></div>
					<div><b>${c.lastSeen ? esc(LA.agoShort(c.lastSeen)) : '—'}</b><span>last seen</span></div>
					<div><b>${(c.services || []).length}</b><span>service${(c.services || []).length === 1 ? '' : 's'}</span></div>
				</div>
				${c.hourly ? hourlyBars(c.hourly) : ''}
				<button class="link" id="aiSearchPattern">Show all occurrences in the explorer →</button>
			</section>
			<section class="panel-sec"><div class="sec-title"><span>Entities</span></div>${entityChips(c.entities)}
				${(c.keywords || []).length ? `<div class="ent-row"><span class="ent-k">Keywords</span>${c.keywords.map((k) => `<span class="kw">${esc(k)}</span>`).join('')}</div>` : ''}
			</section>
			<section class="panel-sec"><div class="sec-title"><span>Similar errors</span><span class="ai-muted">TF-IDF similarity</span></div>
				${(c.similar || []).length ? c.similar.map((x) => logLine(x, true)).join('') : '<p class="ai-muted">No similar errors in the last 24 hours.</p>'}
			</section>
			<section class="panel-sec"><div class="sec-title"><span>Same trace</span><span class="ai-muted">${esc(r.traceId || 'no trace ID')}</span></div>
				${(c.trace || []).length ? c.trace.map((x) => logLine(x, true)).join('') : '<p class="ai-muted">No other events share this trace ID.</p>'}
			</section>
			<section class="panel-sec"><div class="sec-title"><span>Nearby in ${esc(r.service)}</span><span class="ai-muted">warnings & errors ±5 min</span></div>
				${(c.nearby || []).length ? c.nearby.map((x) => logLine(x, true)).join('') : '<p class="ai-muted">Nothing else from this service around that time.</p>'}
			</section>`;
		}
		bodyEl.innerHTML = html;
	}

	async function openPanel(id) {
		ai.panelId = id;
		ai.panelRecord = ai.panelContext = ai.panelInsight = null;
		ai.panelLoading = false;
		document.body.classList.add('ai-open');
		$('aiPanel').setAttribute('aria-hidden', 'false');
		$('aiPanelBody').scrollTop = 0;
		renderPanel();
		try {
			const [rec, ctx, latest] = await Promise.all([
				LA.api('/api/logs/' + id), LA.api(`/api/ai/logs/${id}/context`), getOrNull(`/api/ai/logs/${id}/latest`)
			]);
			if (ai.panelId !== id) return; // another error was opened meanwhile
			ai.panelRecord = rec;
			ai.panelContext = ctx;
			ai.panelInsight = latest;
			renderPanel();
		} catch (e) {
			$('aiPanelBody').innerHTML = `<div class="ai-note">Could not load this log: ${esc(e.message)}</div>`;
		}
	}

	async function runPanel() {
		const id = ai.panelId;
		ai.panelLoading = true;
		renderPanel();
		try {
			const res = await post(`/api/ai/logs/${id}?force=${ai.panelInsight ? 'true' : 'false'}`);
			if (ai.panelId === id) ai.panelInsight = res;
		} catch (e) {
			LA.toast('Analysis failed: ' + e.message);
		} finally {
			ai.panelLoading = false;
			if (ai.panelId === id) renderPanel();
		}
	}

	function closePanel() {
		document.body.classList.remove('ai-open');
		$('aiPanel').setAttribute('aria-hidden', 'true');
		ai.panelId = null;
	}

	// ==========================================================
	// EVENTS
	// ==========================================================
	document.addEventListener('click', (ev) => {
		const b = ev.target.closest('[data-analyze]');
		if (b) {
			ev.preventDefault();
			ev.stopPropagation();
			openPanel(Number(b.dataset.analyze));
		}
	}, true);

	$('aiRun').addEventListener('click', () => runOverview(!!ai.overview));
	$('aiCopy').addEventListener('click', () => ai.overview && copy(markdown(ai.overview,
		`Error analysis: ${LA.RANGE_LABEL[ai.overview.range] || ai.overview.range}`,
		[`**Window:** ${LA.RANGE_LABEL[ai.overview.range] || ai.overview.range}, generated ${new Date(ai.overview.generatedAt).toLocaleString()}`])));
	document.addEventListener('la:range', loadLatestOverview);

	$('aiPanelClose').addEventListener('click', closePanel);
	$('aiPanelBackdrop').addEventListener('click', closePanel);
	document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && document.body.classList.contains('ai-open')) closePanel(); });
	$('aiPanelBody').addEventListener('click', (ev) => {
		if (ev.target.closest('#aiPanelRun')) { runPanel(); return; }
		if (ev.target.closest('#aiSearchPattern') && ai.panelContext) {
			const words = ai.panelContext.template.replace(/<[A-Z]+>/g, ' ').split(/[^A-Za-z]+/).filter((w) => w.length > 3).slice(0, 4).join(' ');
			closePanel();
			LA.filterExplorer({ q: words, service: ai.panelRecord.service });
			return;
		}
		const chip = ev.target.closest('[data-q]');
		if (chip) { closePanel(); LA.filterExplorer({ q: chip.dataset.q }); return; }
		const line = ev.target.closest('[data-open]');
		if (line) openPanel(Number(line.dataset.open));
	});
	$('aiTicket').addEventListener('click', () => {
		const r = ai.panelRecord;
		if (!r) return;
		const t = LA.fmtLogTime(r.timestamp);
		const head = [`**Service:** ${r.service}  `, `**Level:** ${r.level}  `, `**When:** ${t.full} ${t.time}  `, `**Trace ID:** ${r.traceId || '-'}  `, `**Log ID:** ${r.id}`, '', '```', r.rawLine, '```'];
		if (ai.panelContext) head.push('', `Pattern seen ${ai.panelContext.occurrences24h}× in the last 24h: \`${ai.panelContext.template}\``);
		if (!ai.panelInsight) { copy(['## ' + r.level + ' in ' + r.service, '', ...head].join('\n')); return; }
		copy(markdown(ai.panelInsight, `${r.level} in ${r.service}: ${r.message.split('\n')[0].slice(0, 90)}`, head));
	});

	// boot
	(async () => {
		try { ai.status = await LA.api('/api/ai/status'); } catch (e) { ai.status = { configured: false, model: '-' }; }
		await loadLatestOverview();
	})();

	window.LA_AI = ai;
})();
