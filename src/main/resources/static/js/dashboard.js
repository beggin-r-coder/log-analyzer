/* AI Log Analyzer – live dashboard. Data comes from the Spring Boot REST API backed by H2. */
(function () {
	'use strict';

	// ==========================================================
	// STATE
	// ==========================================================
	const REFRESH_MS = 5000;
	const TZ_OFFSET = -new Date().getTimezoneOffset(); // minutes east of UTC, e.g. IST = 330
	const RANGE_LABEL = { '15m': 'Last 15 minutes', '1h': 'Last hour', '6h': 'Last 6 hours', '24h': 'Last 24 hours', '7d': 'Last 7 days' };
	const LEVELS = ['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG'];
	const C = {
		info: '#10b981', warn: '#f59e0b', error: '#ef4444', fatal: '#9f1239', debug: '#94a3b8',
		text: '#64748b', faint: '#94a3b8', grid: 'rgba(15,23,42,0.06)'
	};
	const LEVEL_COLOR = { FATAL: C.fatal, ERROR: C.error, WARN: C.warn, INFO: C.info, DEBUG: C.debug };

	const state = {
		range: load('range', '24h'),
		summary: null,
		serviceSort: { key: 'errorRate', asc: false },
		explorer: { q: '', levels: new Set(), service: '', category: '', sort: 'timestamp', dir: 'desc', page: 0, size: 50 },
		maxSeenId: 0,
		openRows: new Set(),
		simulatorRunning: true
	};

	const $ = (id) => document.getElementById(id);
	const nf = new Intl.NumberFormat('en-US');
	const fmtPct = (v) => (v < 10 ? v.toFixed(2) : v.toFixed(1)) + '%';

	function load(k, d) { try { return localStorage.getItem('la.' + k) || d; } catch (e) { return d; } }
	function save(k, v) { try { localStorage.setItem('la.' + k, v); } catch (e) { /* ignore */ } }

	function esc(s) {
		return s == null ? '' : String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
	}

	async function api(path, opts) {
		const res = await fetch(path, opts);
		if (!res.ok) {
			let msg = res.status + ' ' + res.statusText;
			try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
			throw new Error(msg);
		}
		return res.json();
	}

	function toast(msg) {
		const t = $('toast');
		t.textContent = msg;
		t.classList.add('show');
		clearTimeout(toast._t);
		toast._t = setTimeout(() => t.classList.remove('show'), 3200);
	}

	// ==========================================================
	// TIME FORMATTING  (all in the viewer's local time zone)
	// ==========================================================
	const fmtHour = (d) => d.toLocaleTimeString('en-US', { hour: 'numeric' });                       // 1 PM
	const fmtHourMin = (d) => d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }); // 1:05 PM
	const fmtDay = (d) => d.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric', month: 'short' }); // Tue, Nov 4

	/** Axis label for a bucket; returns two lines at midnight so day changes are obvious. */
	function bucketLabel(t, bucketMinutes) {
		const d = new Date(t);
		const midnight = d.getHours() === 0 && d.getMinutes() === 0;
		if (bucketMinutes >= 360) {
			return [d.toLocaleDateString('en-US', { weekday: 'short' }) + ' ' + fmtHour(d), d.toLocaleDateString('en-US', { day: 'numeric', month: 'short' })];
		}
		const main = bucketMinutes >= 60 ? fmtHour(d) : fmtHourMin(d);
		return midnight ? [main, d.toLocaleDateString('en-US', { day: 'numeric', month: 'short' })] : main;
	}

	function bucketRange(t, bucketMinutes) {
		const a = new Date(t), b = new Date(t + bucketMinutes * 60000);
		const f = bucketMinutes >= 60 && a.getMinutes() === 0 ? fmtHour : fmtHourMin;
		return `${f(a)} – ${f(b)} · ${fmtDay(a)}`;
	}

	function fmtLogTime(ms) {
		const d = new Date(ms);
		return {
			time: d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' }),
			date: fmtDay(d) + ' · .' + String(d.getMilliseconds()).padStart(3, '0')
		};
	}

	function ago(ms) {
		const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
		if (s < 60) return s + 's ago';
		if (s < 3600) return Math.round(s / 60) + 'm ago';
		if (s < 86400) return Math.round(s / 3600) + 'h ago';
		return Math.round(s / 86400) + 'd ago';
	}

	// ==========================================================
	// CHARTS
	// ==========================================================
	Chart.defaults.font.family = "'Inter', system-ui, sans-serif";
	Chart.defaults.font.size = 12;
	Chart.defaults.color = C.text;
	Chart.defaults.borderColor = C.grid;
	Object.assign(Chart.defaults.plugins.tooltip, {
		backgroundColor: 'rgba(15, 23, 42, 0.92)', padding: 12, cornerRadius: 10, titleColor: '#e6ebf5',
		bodyColor: '#cbd5e1', boxPadding: 4
	});
	Chart.defaults.plugins.tooltip.titleFont = { weight: '600' };

	const gradient = (c, stops) => {
		const { chartArea, ctx } = c.chart;
		if (!chartArea) return stops[0][1];
		const g = ctx.createLinearGradient(0, chartArea.top, 0, chartArea.bottom);
		stops.forEach(([o, col]) => g.addColorStop(o, col));
		return g;
	};

	let trendBuckets = [], trendBucketMin = 60, peakIdx = -1;
	const trendChart = new Chart($('trendChart'), {
		type: 'line',
		data: {
			labels: [], datasets: [
				{
					label: 'Errors', data: [], borderColor: '#f43f5e', borderWidth: 2.5, fill: true, tension: 0.35,
					backgroundColor: (c) => gradient(c, [[0, 'rgba(244,63,94,0.22)'], [0.6, 'rgba(244,63,94,0.04)'], [1, 'rgba(244,63,94,0)']]),
					pointRadius: (c) => c.dataIndex === peakIdx ? 5 : 0, pointBackgroundColor: '#f43f5e', pointBorderColor: '#fff',
					pointBorderWidth: 2, pointHoverRadius: 6
				},
				{
					label: 'Warnings', data: [], borderColor: '#f59e0b', borderWidth: 1.8, borderDash: [5, 4], fill: false,
					tension: 0.35, pointRadius: 0, pointHoverRadius: 5, pointBackgroundColor: '#f59e0b'
				}
			]
		},
		options: {
			responsive: true, maintainAspectRatio: false, animation: { duration: 600 },
			interaction: { mode: 'index', intersect: false },
			plugins: {
				legend: { display: false },
				tooltip: {
					callbacks: {
						title: (items) => items.length ? bucketRange(trendBuckets[items[0].dataIndex], trendBucketMin) : '',
						label: (c) => ` ${c.dataset.label}: ${nf.format(c.raw)}`
					}
				}
			},
			scales: {
				x: { grid: { display: false }, border: { display: false }, ticks: { color: C.faint, maxRotation: 0, autoSkip: true, autoSkipPadding: 18 } },
				y: { beginAtZero: true, grid: { color: C.grid, drawTicks: false }, border: { display: false }, ticks: { color: C.faint, padding: 10, precision: 0 } }
			}
		}
	});

	const donutChart = new Chart($('logPieChart'), {
		type: 'doughnut',
		data: { labels: LEVELS, datasets: [{ data: [0, 0, 0, 0, 0], backgroundColor: LEVELS.map((l) => LEVEL_COLOR[l]), borderColor: '#fff', borderWidth: 4, hoverOffset: 8, borderRadius: 6, spacing: 2 }] },
		options: {
			responsive: true, maintainAspectRatio: false, cutout: '74%', animation: { duration: 600 },
			plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => ` ${c.label}: ${nf.format(c.raw)}` } } },
			onClick: (e, els) => { if (els.length) filterExplorer({ levels: [LEVELS[els[0].index]] }); }
		}
	});

	let barCategories = [];
	const barChart = new Chart($('errorBarChart'), {
		type: 'bar',
		data: {
			labels: [], datasets: [{
				label: 'Errors', data: [], borderRadius: 8, borderSkipped: false, maxBarThickness: 24, categoryPercentage: 0.75,
				backgroundColor: (c) => {
					const { chartArea, ctx } = c.chart;
					if (!chartArea) return '#818cf8';
					const g = ctx.createLinearGradient(chartArea.left, 0, chartArea.right, 0);
					const hot = c.dataIndex === 0;
					g.addColorStop(0, hot ? 'rgba(244,63,94,0.45)' : 'rgba(99,102,241,0.45)');
					g.addColorStop(1, hot ? 'rgba(225,29,72,0.95)' : 'rgba(99,102,241,0.95)');
					return g;
				}
			}]
		},
		options: {
			indexAxis: 'y', responsive: true, maintainAspectRatio: false, animation: { duration: 600 },
			onHover: (e, els) => { e.native.target.style.cursor = els.length ? 'pointer' : 'default'; },
			onClick: (e, els) => { if (els.length) filterExplorer({ category: barCategories[els[0].index], levels: ['ERROR', 'FATAL'] }); },
			plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => ` ${nf.format(c.raw)} errors · click to view logs` } } },
			scales: {
				x: { beginAtZero: true, grid: { color: C.grid, drawTicks: false }, border: { display: false }, ticks: { padding: 8, color: C.faint, precision: 0 } },
				y: { grid: { display: false }, border: { display: false }, ticks: { color: '#334155', font: { size: 12 }, padding: 10 } }
			}
		}
	});

	// ==========================================================
	// DASHBOARD RENDERING
	// ==========================================================
	function countTo(el, target) {
		const from = Number(el.dataset.v || 0);
		el.dataset.v = target;
		if (from === target) { el.textContent = nf.format(target); return; }
		const start = performance.now(), dur = 700;
		const tick = (t) => {
			const p = Math.min(1, (t - start) / dur), e = 1 - Math.pow(1 - p, 3);
			el.textContent = nf.format(Math.round(from + (target - from) * e));
			if (p < 1) requestAnimationFrame(tick);
		};
		requestAnimationFrame(tick);
	}

	function renderSummary(s) {
		state.summary = s;
		const t = s.totals, total = t.total || 0;
		const pct = (v) => total ? v * 100 / total : 0;
		const errors = t.error + t.fatal;

		countTo($('kpiTotal'), total);
		countTo($('kpiInfo'), t.info);
		countTo($('kpiWarn'), t.warn);
		countTo($('kpiError'), errors);
		const minutes = Math.max(1, (s.to - s.from) / 60000);
		$('kpiRate').textContent = (total / minutes).toFixed(1) + '/min';
		[['info', t.info], ['warn', t.warn], ['error', errors]].forEach(([k, v]) => {
			$(k + 'Share').textContent = fmtPct(pct(v));
			$(k + 'Meter').style.width = Math.max(pct(v), 1.5) + '%';
		});
		$('fatalNote').textContent = t.fatal ? `incl. ${nf.format(t.fatal)} fatal` : 'of total volume';

		renderHealth(s);
		renderTrend(s);
		renderDonut(t);
		renderCategories(s.categories);
		renderIncidents(s.categories);
		renderServices();
		renderSimulator(s.simulator);

		$('serviceCount').textContent = s.services.length;
		$('timestamp').textContent = s.lastEventAt ? 'Last event ' + ago(s.lastEventAt) : 'No events yet';
		$('rangeChip').textContent = RANGE_LABEL[s.range] || s.range;
	}

	function renderHealth(s) {
		const score = Math.max(0, Math.min(100, s.healthScore));
		const bar = $('gaugeBar'), circ = 2 * Math.PI * 92;
		bar.style.strokeDasharray = circ;
		let gc, s1, s2, label;
		if (score >= 85) { gc = '#059669'; s1 = '#10b981'; s2 = '#06b6d4'; label = 'Healthy'; }
		else if (score >= 60) { gc = '#d97706'; s1 = '#f59e0b'; s2 = '#f97316'; label = 'Degraded'; }
		else { gc = '#e11d48'; s1 = '#fb7185'; s2 = '#e11d48'; label = 'Critical'; }
		$('healthCard').style.setProperty('--gc', gc);
		$('gStop1').setAttribute('stop-color', s1);
		$('gStop2').setAttribute('stop-color', s2);
		$('statusText').textContent = label;
		$('healthScore').textContent = score.toFixed(1);
		bar.style.strokeDashoffset = circ * (1 - score / 100);
		$('errorRate').textContent = fmtPct(s.errorRate);
		$('warnRate').textContent = fmtPct(s.warnRate);
	}

	function renderTrend(s) {
		trendBuckets = s.trend.map((p) => p.t);
		trendBucketMin = s.bucketMinutes;
		const errs = s.trend.map((p) => p.errors);
		peakIdx = errs.length ? errs.indexOf(Math.max(...errs)) : -1;
		trendChart.data.labels = s.trend.map((p) => bucketLabel(p.t, s.bucketMinutes));
		trendChart.data.datasets[0].data = errs;
		trendChart.data.datasets[1].data = s.trend.map((p) => p.warnings);
		trendChart.update();

		const unit = s.bucketMinutes >= 1440 ? 'day' : s.bucketMinutes >= 60 ? (s.bucketMinutes === 60 ? 'hour' : (s.bucketMinutes / 60) + ' hours') : s.bucketMinutes + ' min';
		$('trendSub').textContent = `Errors & warnings per ${unit}, in your local time`;
		if (peakIdx >= 0 && errs[peakIdx] > 0) {
			const pt = s.trend[peakIdx].t;
			const label = s.bucketMinutes >= 360 ? fmtDay(new Date(pt)) + ' ' + fmtHour(new Date(pt))
				: s.bucketMinutes >= 60 ? fmtHour(new Date(pt)) : fmtHourMin(new Date(pt));
			$('peakChip').innerHTML = `<span class="dot" style="background:${C.error};box-shadow:0 0 0 3px rgba(239,68,68,0.18)"></span>Peak ${esc(label)} · ${nf.format(errs[peakIdx])} errors`;
		} else {
			$('peakChip').textContent = 'No errors in range';
		}
	}

	function renderDonut(t) {
		const vals = [t.fatal, t.error, t.warn, t.info, t.debug + t.trace];
		donutChart.data.datasets[0].data = vals;
		donutChart.update();
		$('donutTotal').textContent = nf.format(t.total);
		$('donutLegend').innerHTML = LEVELS.map((l, i) => `
			<div class="legend-row">
				<span class="sw" style="background:${LEVEL_COLOR[l]}"></span>
				<span class="name">${l}</span>
				<span class="num">${nf.format(vals[i])}</span>
				<span class="pct">${fmtPct(t.total ? vals[i] * 100 / t.total : 0)}</span>
			</div>`).join('');
	}

	function renderCategories(cats) {
		const top = cats.slice(0, 10);
		barCategories = top.map((c) => c.name);
		barChart.data.labels = barCategories;
		barChart.data.datasets[0].data = top.map((c) => c.count);
		barChart.update();
		$('categoryChip').textContent = cats.length + (cats.length === 1 ? ' category' : ' categories');
	}

	function renderIncidents(cats) {
		const list = $('incidentList');
		const items = cats.slice(0, 6);
		if (!items.length) {
			list.innerHTML = '<div class="empty">No errors in this window. All systems nominal.</div>';
			$('incidentChip').style.display = 'none';
			return;
		}
		$('incidentChip').style.display = '';
		const hot = items.filter((c) => c.severity === 'CRITICAL' || c.severity === 'HIGH').length;
		$('incidentChip').textContent = hot ? `${hot} need attention` : 'No critical incidents';
		list.innerHTML = items.map((c) => `
			<div class="incident sev-${esc(c.severity)}" data-category="${esc(c.name)}" title="View these logs">
				<div class="incident-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l9.5 17h-19z"/><path d="M12 10v4M12 17h.01"/></svg></div>
				<div class="incident-main">
					<div class="incident-name">${esc(c.name)}</div>
					<div class="incident-meta"><strong>${nf.format(c.count)}</strong> errors · ${c.share.toFixed(1)}% · last ${ago(c.lastSeen)} · ${esc(c.services.slice(0, 2).join(', '))}${c.services.length > 2 ? ' +' + (c.services.length - 2) : ''}</div>
				</div>
				<span class="sev">${esc(c.severity)}</span>
			</div>`).join('');
	}

	function renderServices() {
		const s = state.summary;
		if (!s) return;
		const { key, asc } = state.serviceSort;
		const rank = { CRITICAL: 3, DEGRADED: 2, HEALTHY: 1 };
		const rows = [...s.services].sort((a, b) => {
			const va = key === 'status' ? rank[a.status] : a[key], vb = key === 'status' ? rank[b.status] : b[key];
			return (va > vb ? 1 : va < vb ? -1 : 0) * (asc ? 1 : -1);
		});
		const max = Math.max(1, ...rows.map((r) => r.errorRate));
		document.querySelector('#serviceTable tbody').innerHTML = rows.map((r) => `
			<tr class="log-row" data-service="${esc(r.name)}" title="Filter logs by ${esc(r.name)}">
				<td class="mono" style="font-size:12.5px;font-weight:600">${esc(r.name)}</td>
				<td class="num-cell">${nf.format(r.total)}</td>
				<td class="num-cell" style="color:var(--error)">${nf.format(r.errors)}</td>
				<td><div class="bar-cell"><span class="num-cell" style="width:48px">${r.errorRate.toFixed(2)}%</span><div class="share-bar"><span style="width:${r.errorRate / max * 100}%"></span></div></div></td>
				<td><span class="sev sev-${esc(r.status)}">${esc(r.status)}</span></td>
			</tr>`).join('') || '<tr><td colspan="5" class="empty">No services reporting.</td></tr>';
	}

	function renderSimulator(sim) {
		if (!sim) return;
		state.simulatorRunning = sim.running;
		$('livePill').classList.toggle('paused', !sim.running);
		$('liveText').textContent = sim.seeding ? 'Seeding…' : sim.running ? 'Live' : 'Paused';
		const banner = $('incidentBanner');
		if (sim.incidentService && sim.running) {
			const m = Math.floor(sim.incidentEndsInSec / 60), sec = sim.incidentEndsInSec % 60;
			$('incidentText').textContent = `Active incident: ${sim.incidentService} is throwing errors · simulated, clears in ${m}m ${sec}s`;
			banner.classList.add('show');
		} else {
			banner.classList.remove('show');
		}
	}

	async function refreshDashboard() {
		try {
			renderSummary(await api(`/api/dashboard?range=${encodeURIComponent(state.range)}&tz=${TZ_OFFSET}`));
		} catch (e) {
			$('timestamp').textContent = 'Connection lost – retrying…';
		}
	}

	// ==========================================================
	// LOG EXPLORER
	// ==========================================================
	function explorerParams(extra) {
		const e = state.explorer, p = new URLSearchParams();
		p.set('range', state.range);
		if (e.q) p.set('q', e.q);
		if (e.levels.size) p.set('levels', [...e.levels].join(','));
		if (e.service) p.set('service', e.service);
		if (e.category) p.set('category', e.category);
		p.set('sort', e.sort);
		p.set('dir', e.dir);
		Object.entries(extra || {}).forEach(([k, v]) => p.set(k, v));
		return p;
	}

	function highlight(text) {
		const safe = esc(text);
		const terms = state.explorer.q.trim().split(/\s+/).filter((t) => t.length > 1);
		if (!terms.length) return safe;
		const re = new RegExp('(' + terms.map((t) => esc(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')', 'gi');
		return safe.replace(re, '<mark>$1</mark>');
	}

	async function refreshLogs(opts) {
		const e = state.explorer;
		let data;
		try {
			data = await api('/api/logs?' + explorerParams({ page: e.page, size: e.size }));
		} catch (err) {
			$('pageInfo').textContent = 'Error: ' + err.message;
			return;
		}
		const tbody = document.querySelector('#logTable tbody');
		const highlightNew = opts && opts.live && state.maxSeenId > 0;
		let maxId = state.maxSeenId;
		tbody.innerHTML = data.content.map((r) => {
			const t = fmtLogTime(r.timestamp);
			const fresh = highlightNew && r.id > state.maxSeenId ? ' fresh' : '';
			maxId = Math.max(maxId, r.id);
			const open = state.openRows.has(r.id);
			return `
			<tr class="log-row${fresh}${open ? ' open' : ''}" data-id="${r.id}">
				<td class="time">${esc(t.time)}<span class="d">${esc(t.date)}</span></td>
				<td><span class="lvl L-${esc(r.level)}">${esc(r.level)}</span></td>
				<td class="svc">${highlight(r.service)}</td>
				<td class="cat">${r.category ? highlight(r.category) : '<span style="color:var(--text-faint)">—</span>'}</td>
				<td class="msg"><div>${highlight(r.message)}</div></td>
			</tr>${open ? detailRow(r) : ''}`;
		}).join('');
		state.maxSeenId = maxId;
		state.rowsById = Object.fromEntries(data.content.map((r) => [r.id, r]));

		$('logEmpty').style.display = data.content.length ? 'none' : 'block';
		const from = data.totalElements ? e.page * e.size + 1 : 0;
		const to = Math.min(data.totalElements, (e.page + 1) * e.size);
		$('pageInfo').textContent = `${nf.format(from)}–${nf.format(to)} of ${nf.format(data.totalElements)} logs · page ${e.page + 1} of ${Math.max(1, data.totalPages)}`;
		$('prevPage').disabled = e.page === 0;
		$('nextPage').disabled = e.page + 1 >= data.totalPages;
		$('exportBtn').href = '/api/logs/export?' + explorerParams();
	}

	function detailRow(r) {
		const f = (k, v, filter) => `<div><div class="k">${k}</div><div class="v">${v == null || v === '' ? '—' : filter ? `<a data-q="${esc(v)}" title="Search for this">${esc(v)}</a>` : esc(v)}</div></div>`;
		return `<tr class="detail"><td colspan="5">
			<div class="detail-grid">
				${f('Timestamp (local)', new Date(r.timestamp).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'medium' }))}
				${f('Detected format', r.formatLabel)}
				${f('Trace / request ID', r.traceId, true)}
				${f('Host', r.host, true)}
				${f('Client IP', r.clientIp, true)}
				${f('Thread', r.thread)}
				${f('Logger', r.logger)}
				${f('Record ID', r.id)}
			</div>
			<pre class="raw">${esc(r.rawLine)}</pre>
		</td></tr>`;
	}

	function setSortIndicators(tableId, key, asc) {
		document.querySelectorAll(`#${tableId} th[data-sort]`).forEach((th) => {
			const on = th.dataset.sort === key;
			th.classList.toggle('sorted', on);
			th.querySelector('.arrow').textContent = on ? (asc ? '↑' : '↓') : '↕';
		});
	}

	function syncExplorerControls() {
		const e = state.explorer;
		$('logSearch').value = e.q;
		$('serviceFilter').value = e.service;
		$('categoryFilter').value = e.category;
		document.querySelectorAll('.lvl-toggle').forEach((b) => b.classList.toggle('on', e.levels.has(b.dataset.level)));
		setSortIndicators('logTable', e.sort, e.dir === 'asc');
	}

	/** Called from charts / incidents / service table to drill into the explorer. */
	function filterExplorer(f) {
		const e = state.explorer;
		e.q = f.q !== undefined ? f.q : '';
		e.levels = new Set(f.levels || []);
		e.service = f.service || '';
		e.category = f.category || '';
		e.page = 0;
		e.sort = 'timestamp';
		e.dir = 'desc';
		ensureOption($('serviceFilter'), e.service);
		ensureOption($('categoryFilter'), e.category);
		syncExplorerControls();
		refreshLogs();
		$('explorer').scrollIntoView({ behavior: 'smooth', block: 'start' });
	}

	function ensureOption(select, value) {
		if (value && ![...select.options].some((o) => o.value === value)) {
			select.add(new Option(value, value));
		}
	}

	async function refreshFacets() {
		try {
			const f = await api('/api/logs/facets');
			[['serviceFilter', f.services, 'All services'], ['categoryFilter', f.categories, 'All categories']].forEach(([id, values, all]) => {
				const sel = $(id), cur = sel.value;
				sel.innerHTML = `<option value="">${all}</option>` + values.map((v) => `<option value="${esc(v)}">${esc(v)}</option>`).join('');
				ensureOption(sel, cur);
				sel.value = cur;
			});
		} catch (e) { /* ignore, retried later */ }
	}

	// ==========================================================
	// EVENTS
	// ==========================================================
	function initEvents() {
		// range selector
		document.querySelectorAll('#rangeSeg button').forEach((b) => {
			b.classList.toggle('active', b.dataset.range === state.range);
			b.addEventListener('click', () => {
				state.range = b.dataset.range;
				save('range', state.range);
				document.querySelectorAll('#rangeSeg button').forEach((x) => x.classList.toggle('active', x === b));
				state.explorer.page = 0;
				refreshDashboard();
				refreshLogs();
			});
		});

		// level toggles
		$('levelFilters').innerHTML = LEVELS.map((l) => `<button class="lvl-toggle" data-level="${l}" style="--lc:${LEVEL_COLOR[l]}">${l}</button>`).join('');
		$('levelFilters').addEventListener('click', (ev) => {
			const b = ev.target.closest('.lvl-toggle');
			if (!b) return;
			const lv = state.explorer.levels;
			lv.has(b.dataset.level) ? lv.delete(b.dataset.level) : lv.add(b.dataset.level);
			b.classList.toggle('on');
			state.explorer.page = 0;
			refreshLogs();
		});

		// search (debounced)
		let debounce;
		$('logSearch').addEventListener('input', (ev) => {
			clearTimeout(debounce);
			debounce = setTimeout(() => {
				state.explorer.q = ev.target.value.trim();
				state.explorer.page = 0;
				refreshLogs();
			}, 300);
		});

		$('serviceFilter').addEventListener('change', (ev) => { state.explorer.service = ev.target.value; state.explorer.page = 0; refreshLogs(); });
		$('categoryFilter').addEventListener('change', (ev) => { state.explorer.category = ev.target.value; state.explorer.page = 0; refreshLogs(); });
		$('pageSize').addEventListener('change', (ev) => { state.explorer.size = Number(ev.target.value); state.explorer.page = 0; refreshLogs(); });
		$('prevPage').addEventListener('click', () => { if (state.explorer.page > 0) { state.explorer.page--; refreshLogs(); } });
		$('nextPage').addEventListener('click', () => { state.explorer.page++; refreshLogs(); });
		$('resetBtn').addEventListener('click', () => filterExplorer({}));

		// explorer sorting (server side)
		document.querySelectorAll('#logTable th[data-sort]').forEach((th) => th.addEventListener('click', () => {
			const e = state.explorer, key = th.dataset.sort;
			if (e.sort === key) {
				e.dir = e.dir === 'asc' ? 'desc' : 'asc';
			} else {
				e.sort = key;
				e.dir = key === 'timestamp' || key === 'level' ? 'desc' : 'asc';
			}
			e.page = 0;
			setSortIndicators('logTable', e.sort, e.dir === 'asc');
			refreshLogs();
		}));

		// expand / collapse a log row, or click a detail value to search for it
		document.querySelector('#logTable tbody').addEventListener('click', (ev) => {
			const link = ev.target.closest('a[data-q]');
			if (link) {
				filterExplorer({ q: link.dataset.q });
				return;
			}
			const tr = ev.target.closest('tr.log-row');
			if (!tr) return;
			const id = Number(tr.dataset.id);
			if (state.openRows.has(id)) {
				state.openRows.delete(id);
				tr.classList.remove('open');
				if (tr.nextElementSibling && tr.nextElementSibling.classList.contains('detail')) tr.nextElementSibling.remove();
			} else {
				state.openRows.add(id);
				tr.classList.add('open');
				tr.insertAdjacentHTML('afterend', detailRow(state.rowsById[id]));
			}
		});

		// service table: client-side sort + drill-down
		document.querySelectorAll('#serviceTable th[data-sort]').forEach((th) => th.addEventListener('click', () => {
			const s = state.serviceSort, key = th.dataset.sort;
			s.asc = s.key === key ? !s.asc : key === 'name';
			s.key = key;
			setSortIndicators('serviceTable', s.key, s.asc);
			renderServices();
		}));
		document.querySelector('#serviceTable tbody').addEventListener('click', (ev) => {
			const tr = ev.target.closest('tr[data-service]');
			if (tr) filterExplorer({ service: tr.dataset.service });
		});

		$('incidentList').addEventListener('click', (ev) => {
			const it = ev.target.closest('.incident');
			if (it) filterExplorer({ category: it.dataset.category, levels: ['ERROR', 'FATAL'] });
		});

		// simulator controls
		$('livePill').addEventListener('click', async () => {
			try {
				renderSimulator(await api('/api/simulator/' + (state.simulatorRunning ? 'pause' : 'resume'), { method: 'POST' }));
				toast(state.simulatorRunning ? 'Live log stream resumed' : 'Live log stream paused');
			} catch (e) { toast('Failed: ' + e.message); }
		});
		$('incidentBtn').addEventListener('click', async () => {
			try {
				const s = await api('/api/simulator/incident?minutes=5', { method: 'POST' });
				renderSimulator(s);
				toast(`Incident injected into ${s.incidentService} for 5 minutes, watch the 15m/1h view`);
				refreshDashboard();
			} catch (e) { toast('Failed: ' + e.message); }
		});
	}

	// ==========================================================
	// BOOT
	// ==========================================================
	initEvents();
	syncExplorerControls();
	refreshFacets();
	refreshDashboard();
	refreshLogs();

	setInterval(() => {
		if (document.hidden) return;
		refreshDashboard();
		const e = state.explorer;
		if ($('tailToggle').checked && e.page === 0 && e.sort === 'timestamp' && e.dir === 'desc') {
			refreshLogs({ live: true });
		}
	}, REFRESH_MS);
	setInterval(() => { if (!document.hidden) refreshFacets(); }, 60000);
})();
