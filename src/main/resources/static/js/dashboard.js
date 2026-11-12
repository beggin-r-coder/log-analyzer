/* Log Analyzer – live dashboard. Data comes from the Spring Boot REST API backed by H2. */
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

	/** "Today", "Yesterday", "Monday" (within a week) or "" for older dates. */
	function dayLabel(ms) {
		const d = new Date(ms), now = new Date();
		const startOf = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
		const diff = Math.round((startOf(now) - startOf(d)) / 86400000);
		if (diff === 0) return 'Today';
		if (diff === 1) return 'Yesterday';
		if (diff > 1 && diff < 7) return d.toLocaleDateString('en-US', { weekday: 'long' });
		if (diff === -1) return 'Tomorrow';
		return '';
	}
	const fmtFullDate = (ms) => new Date(ms).toLocaleDateString('en-US', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }); // Wed, Nov 4, 2026

	function fmtLogTime(ms) {
		const d = new Date(ms), rel = dayLabel(ms);
		return {
			time: d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' }),
			ms: '.' + String(d.getMilliseconds()).padStart(3, '0'),
			rel,
			full: fmtFullDate(ms),
			date: (rel && rel !== d.toLocaleDateString('en-US', { weekday: 'long' }) ? rel + ' · ' : '') + fmtFullDate(ms)
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

	/** Recolours axes, grids and donut borders for the light or dark theme. */
	const STRONG_TICKS = new Set(['#334155', '#c8d3e6']);
	function applyChartTheme() {
		const dark = document.documentElement.getAttribute('data-theme') === 'dark';
		const tick = dark ? '#7f8ea8' : C.faint, strong = dark ? '#c8d3e6' : '#334155';
		const grid = dark ? 'rgba(148,163,184,0.10)' : C.grid, surface = dark ? '#0d1322' : '#ffffff';
		Chart.defaults.color = dark ? '#9aa8bf' : C.text;
		Chart.defaults.borderColor = grid;
		Object.values(Chart.instances).forEach((ch) => {
			Object.values(ch.options.scales || {}).forEach((sc) => {
				if (sc.ticks) sc.ticks.color = STRONG_TICKS.has(sc.ticks.color) ? strong : tick;
				if (sc.grid && sc.grid.display !== false) sc.grid.color = grid;
			});
			if (ch.config.type === 'doughnut') ch.data.datasets.forEach((d) => { d.borderColor = surface; });
			const legend = ch.options.plugins && ch.options.plugins.legend;
			if (legend && legend.labels) legend.labels.color = Chart.defaults.color;
			ch.update('none');
		});
	}
	document.addEventListener('la:theme', applyChartTheme);

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
					const hot = c.dataIndex === 0, dark = document.documentElement.getAttribute('data-theme') === 'dark';
					if (dark) { // neon
						g.addColorStop(0, hot ? 'rgba(255,77,141,0.45)' : 'rgba(34,211,238,0.35)');
						g.addColorStop(1, hot ? 'rgba(255,46,99,1)' : 'rgba(167,139,250,0.95)');
						return g;
					}
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
				y: {
					grid: { display: false }, border: { display: false }, ticks: {
						color: '#334155', font: { size: 12 }, padding: 10,
						callback: function (v) { const s = this.getLabelForValue(v), max = this.chart.width < 520 ? 15 : 32; return s.length > max ? s.slice(0, max - 1) + '…' : s; }
					}
				}
			}
		}
	});

	// ---------- optional charts ----------
	const volumeChart = new Chart($('volumeChart'), {
		type: 'bar',
		data: {
			labels: [], datasets: [
				{ label: 'Info/Debug', data: [], backgroundColor: 'rgba(16,185,129,0.75)', borderRadius: 3, stack: 's' },
				{ label: 'Warn', data: [], backgroundColor: 'rgba(245,158,11,0.85)', borderRadius: 3, stack: 's' },
				{ label: 'Error', data: [], backgroundColor: 'rgba(239,68,68,0.9)', borderRadius: 3, stack: 's' }
			]
		},
		options: {
			responsive: true, maintainAspectRatio: false, animation: { duration: 500 },
			interaction: { mode: 'index', intersect: false },
			plugins: {
				legend: { display: false },
				tooltip: { callbacks: { title: (items) => items.length ? bucketRange(trendBuckets[items[0].dataIndex], trendBucketMin) : '', label: (c) => ` ${c.dataset.label}: ${nf.format(c.raw)}` } }
			},
			scales: {
				x: { stacked: true, grid: { display: false }, border: { display: false }, ticks: { color: C.faint, maxRotation: 0, autoSkip: true, autoSkipPadding: 18 } },
				y: { stacked: true, beginAtZero: true, grid: { color: C.grid, drawTicks: false }, border: { display: false }, ticks: { color: C.faint, padding: 10, precision: 0 } }
			}
		}
	});

	const FORMAT_COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#14b8a6', '#f97316', '#ec4899', '#84cc16', '#64748b', '#a855f7'];
	const formatChart = new Chart($('formatChart'), {
		type: 'doughnut',
		data: { labels: [], datasets: [{ data: [], backgroundColor: FORMAT_COLORS, borderColor: '#fff', borderWidth: 3, spacing: 1, borderRadius: 4 }] },
		options: { responsive: true, maintainAspectRatio: false, cutout: '72%', animation: { duration: 500 }, plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => ` ${c.label}: ${nf.format(c.raw)}` } } } }
	});

	const SLO = 3;
	const errorRateChart = new Chart($('errorRateChart'), {
		type: 'line',
		data: {
			labels: [], datasets: [
				{ label: 'Error rate', data: [], borderColor: '#8b5cf6', borderWidth: 2.2, tension: 0.35, fill: true, pointRadius: 0, pointHoverRadius: 5,
					backgroundColor: (c) => gradient(c, [[0, 'rgba(139,92,246,0.22)'], [1, 'rgba(139,92,246,0)']]) },
				{ label: 'SLO', data: [], borderColor: 'rgba(225,29,72,0.6)', borderDash: [6, 5], borderWidth: 1.4, pointRadius: 0, fill: false }
			]
		},
		options: {
			responsive: true, maintainAspectRatio: false, animation: { duration: 500 }, interaction: { mode: 'index', intersect: false },
			plugins: {
				legend: { display: false },
				tooltip: { callbacks: { title: (items) => items.length ? bucketRange(trendBuckets[items[0].dataIndex], trendBucketMin) : '', label: (c) => ` ${c.dataset.label}: ${Number(c.raw).toFixed(2)}%` } }
			},
			scales: {
				x: { grid: { display: false }, border: { display: false }, ticks: { color: C.faint, maxRotation: 0, autoSkip: true, autoSkipPadding: 18 } },
				y: { beginAtZero: true, grid: { color: C.grid, drawTicks: false }, border: { display: false }, ticks: { color: C.faint, padding: 10, callback: (v) => v + '%' } }
			}
		}
	});

	let svcChartNames = [];
	const svcErrorChart = new Chart($('svcErrorChart'), {
		type: 'bar',
		data: {
			labels: [], datasets: [
				{ label: 'Errors', data: [], backgroundColor: 'rgba(239,68,68,0.85)', borderRadius: 5, maxBarThickness: 22 },
				{ label: 'Warnings', data: [], backgroundColor: 'rgba(245,158,11,0.55)', borderRadius: 5, maxBarThickness: 22 }
			]
		},
		options: {
			responsive: true, maintainAspectRatio: false, animation: { duration: 500 }, interaction: { mode: 'index', intersect: false },
			onHover: (e, els) => { e.native.target.style.cursor = els.length ? 'pointer' : 'default'; },
			onClick: (e, els) => { if (els.length) filterExplorer({ service: svcChartNames[els[0].index] }); },
			plugins: { legend: { position: 'top', align: 'end', labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true } } },
			scales: {
				x: { grid: { display: false }, border: { display: false }, ticks: { color: '#334155', maxRotation: 45, minRotation: 30, font: { size: 11 } } },
				y: { beginAtZero: true, grid: { color: C.grid, drawTicks: false }, border: { display: false }, ticks: { color: C.faint, padding: 8, precision: 0 } }
			}
		}
	});

	function renderOptional(s) {
		const t = s.totals, total = t.total || 0;
		const labels = s.trend.map((p) => bucketLabel(p.t, s.bucketMinutes));

		// volume
		volumeChart.data.labels = labels;
		volumeChart.data.datasets[0].data = s.trend.map((p) => p.total - p.warnings - p.errors);
		volumeChart.data.datasets[1].data = s.trend.map((p) => p.warnings);
		volumeChart.data.datasets[2].data = s.trend.map((p) => p.errors);
		volumeChart.update();
		$('volumeSub').textContent = $('trendSub').textContent.replace('Errors & warnings', 'Events');

		// error rate
		const rates = s.trend.map((p) => p.total ? +(p.errors * 100 / p.total).toFixed(2) : 0);
		errorRateChart.data.labels = labels;
		errorRateChart.data.datasets[0].data = rates;
		errorRateChart.data.datasets[1].data = rates.map(() => SLO);
		errorRateChart.update();
		const breaches = rates.filter((r) => r > SLO).length;
		$('errorRateChip').textContent = breaches ? `${breaches} bucket${breaches > 1 ? 's' : ''} above SLO` : 'Within SLO';
		$('errorRateChip').style.color = breaches ? '#be123c' : '#047857';

		// errors by service
		const svcs = [...s.services].sort((a, b) => b.errors - a.errors);
		svcChartNames = svcs.map((x) => x.name);
		svcErrorChart.data.labels = svcChartNames;
		svcErrorChart.data.datasets[0].data = svcs.map((x) => x.errors);
		svcErrorChart.data.datasets[1].data = svcs.map((x) => x.warnings);
		svcErrorChart.update();

		// formats
		const fmts = s.formats || [];
		formatChart.data.labels = fmts.map((f) => f.label);
		formatChart.data.datasets[0].data = fmts.map((f) => f.count);
		formatChart.update();
		$('formatCount').textContent = fmts.length;
		$('formatLegend').innerHTML = fmts.slice(0, 7).map((f, i) => `
			<div class="legend-row"><span class="sw" style="background:${FORMAT_COLORS[i % FORMAT_COLORS.length]}"></span>
			<span class="name">${esc(f.label)}</span><span class="num">${nf.format(f.count)}</span>
			<span class="pct">${fmtPct(total ? f.count * 100 / total : 0)}</span></div>`).join('');

		// optional KPIs
		const minutes = Math.max(1, (s.to - s.from) / 60000);
		const avg = total / minutes;
		const peakBucket = Math.max(0, ...s.trend.map((p) => p.total));
		const peakPerMin = peakBucket / s.bucketMinutes;
		$('kpiThroughput').textContent = avg.toFixed(1);
		$('throughputPeak').textContent = 'peak ' + peakPerMin.toFixed(1);
		$('throughputMeter').style.width = Math.min(100, peakPerMin ? avg / peakPerMin * 100 : 0) + '%';

		countTo($('kpiFatal'), t.fatal);
		$('fatalShare').textContent = fmtPct(total ? t.fatal * 100 / total : 0);
		$('fatalMeter').style.width = (t.fatal ? Math.max(2, Math.min(100, t.fatal * 10)) : 0) + '%';

		const healthy = s.services.filter((x) => x.status === 'HEALTHY').length;
		$('kpiServices').textContent = `${healthy}/${s.services.length}`;
		$('servicesShare').textContent = s.services.length ? Math.round(healthy * 100 / s.services.length) + '%' : '—';
		$('servicesMeter').style.width = (s.services.length ? healthy * 100 / s.services.length : 0) + '%';
		const bad = s.services.filter((x) => x.status !== 'HEALTHY').map((x) => x.name);
		$('servicesNote').textContent = bad.length ? 'issues: ' + bad.slice(0, 2).join(', ') + (bad.length > 2 ? '…' : '') : 'all services healthy';

		const open = s.categories.filter((c) => c.severity === 'CRITICAL' || c.severity === 'HIGH');
		countTo($('kpiIncidents'), open.length);
		$('incidentsTop').textContent = open.length ? open[0].severity : 'none';
		$('incidentsMeter').style.width = Math.min(100, open.length * 25) + '%';
	}

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

	/**
	 * Change vs the previous window. upIsBad: true for errors/warnings, false for good metrics,
	 * null when direction carries no judgement (volume).
	 */
	function delta(cur, prev, upIsBad) {
		if (prev == null || prev === 0) return null;
		const p = (cur - prev) * 100 / prev;
		const dir = Math.abs(p) < 0.5 ? 'flat' : p > 0 ? 'up' : 'down';
		const cls = dir === 'flat' || upIsBad === null ? 'neutral' : (dir === 'up') === upIsBad ? 'bad' : 'good';
		return { pct: p, dir, cls, arrow: dir === 'up' ? '▲' : dir === 'down' ? '▼' : '■' };
	}

	function renderDelta(id, d, label) {
		const el = $(id);
		if (!el) return;
		if (!d) { el.className = 'kpi-delta'; el.innerHTML = ''; return; }
		el.className = 'kpi-delta ' + d.cls;
		el.innerHTML = `${d.arrow} ${Math.abs(d.pct).toFixed(1)}% <span>${esc(label)}</span>`;
	}

	function renderDeltas(s) {
		const t = s.totals, p = s.previous;
		const label = 'vs previous ' + (s.range === '7d' ? '7 days' : s.range);
		const pv = (k) => (p ? p[k] : null);
		renderDelta('delta-total', delta(t.total, pv('total'), null), label);
		renderDelta('delta-info', delta(t.info, pv('info'), null), label);
		renderDelta('delta-warn', delta(t.warn, pv('warn'), true), label);
		renderDelta('delta-error', delta(t.error + t.fatal, p ? p.error + p.fatal : null, true), label);
		renderDelta('delta-throughput', delta(t.total, pv('total'), null), label);
		renderDelta('delta-fatal', delta(t.fatal, pv('fatal'), true), label);
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
		renderOptional(s);
		renderDeltas(s);

		$('serviceCount').textContent = s.services.length;
		$('timestamp').textContent = s.lastEventAt ? 'Last event ' + ago(s.lastEventAt) : 'No events yet';
		if ($('rangeChip')) $('rangeChip').textContent = RANGE_LABEL[s.range] || s.range;
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
			return true;
		} catch (e) {
			$('timestamp').textContent = 'Connection lost – retrying…';
			return false;
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
				<td class="time">${esc(t.time)}<span class="d">${t.rel ? '<b style="color:#4f46e5">' + esc(t.rel) + '</b> · ' : ''}${esc(t.full)}</span></td>
				<td><span class="lvl L-${esc(r.level)}">${esc(r.level)}</span></td>
				<td class="svc">${highlight(r.service)}</td>
				<td class="cat${r.category ? '' : ' cat-empty'}">${r.category ? highlight(r.category) : '<span style="color:var(--text-faint)">—</span>'}</td>
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
			<div class="detail-actions">${analyzeButton(r)}</div>
			<pre class="raw">${esc(r.rawLine)}</pre>
		</td></tr>`;
	}

	function setSortIndicators(tableId, key, asc) {
		if (tableId === 'logTable' && $('mobileSort')) {
			const v = key + ':' + (asc ? 'asc' : 'desc');
			$('mobileSort').value = [...$('mobileSort').options].some((o) => o.value === v) ? v : 'timestamp:desc';
		}
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
	// TOP BAR: menus + tab highlighting
	// ==========================================================
	function closeMenus(except) {
		document.querySelectorAll('.menu').forEach((m) => {
			if (m === except) return;
			m.hidden = true;
			const btn = document.querySelector(`[aria-controls="${m.id}"]`);
			if (btn) btn.setAttribute('aria-expanded', 'false');
		});
	}

	function initMenus() {
		document.querySelectorAll('[aria-haspopup="menu"]').forEach((btn) => {
			const menu = $(btn.getAttribute('aria-controls'));
			btn.addEventListener('click', (ev) => {
				ev.stopPropagation();
				const open = menu.hidden;
				closeMenus(menu);
				menu.hidden = !open;
				btn.setAttribute('aria-expanded', String(open));
				if (open) {
					const first = menu.querySelector('[aria-checked="true"]') || menu.querySelector('button, a');
					if (first) first.focus();
				}
			});
			menu.addEventListener('click', (ev) => { if (ev.target.closest('a')) closeMenus(); });
		});
		document.addEventListener('click', (ev) => { if (!ev.target.closest('.menu')) closeMenus(); });
		document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape') closeMenus(); });
	}

	/** Underlines the tab for the section currently in view. */
	function initScrollSpy() {
		const tabs = [...document.querySelectorAll('.nav-tabs a[data-nav]')];
		const update = () => {
			let current = 'top';
			// of the sections scrolled into view, the one lowest on the page wins
			// at the very bottom the last section may never reach the top, so count anything on screen
			const atBottom = window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 4;
			const line = atBottom ? window.innerHeight : window.innerHeight * 0.4;
			const reached = ['livefeed', 'explorer'].map(widgetEl)
				.filter((el) => el && !el.classList.contains('w-hidden') && el.getBoundingClientRect().top < line)
				.sort((a, b) => b.getBoundingClientRect().top - a.getBoundingClientRect().top);
			if (reached.length) current = reached[0].dataset.widget;
			tabs.forEach((t) => t.classList.toggle('active', t.dataset.nav === current));
		};
		let raf;
		window.addEventListener('scroll', () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(update); }, { passive: true });
		update();
	}

	// ==========================================================
	// EVENTS
	// ==========================================================
	function initEvents() {
		// dropdown menus in the top bar
		initMenus();

		// time range dropdown
		const syncRange = () => {
			document.querySelectorAll('#rangeMenu [data-range]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.range === state.range)));
			$('rangeLabel').textContent = RANGE_LABEL[state.range] || state.range;
			$('rangeShort').textContent = state.range;
		};
		syncRange();
		document.querySelectorAll('#rangeMenu [data-range]').forEach((b) => {
			b.addEventListener('click', () => {
				state.range = b.dataset.range;
				save('range', state.range);
				syncRange();
				closeMenus();
				document.dispatchEvent(new CustomEvent('la:range', { detail: state.range }));
				state.explorer.page = 0;
				refreshDashboard();
				refreshLogs();
			});
		});

		// tabs: jump to a section, re-showing its widget if it was hidden in Customize
		document.querySelectorAll('.nav-tabs a[data-nav]').forEach((a) => a.addEventListener('click', (ev) => {
			ev.preventDefault();
			const target = a.dataset.nav;
			if (target === 'top') {
				window.scrollTo({ top: 0, behavior: 'smooth' });
			} else {
				if (layout.hidden.has(target)) setWidgetVisible(target, true);
				widgetEl(target).scrollIntoView({ behavior: 'smooth', block: 'start' });
			}
			history.replaceState(null, '', target === 'top' ? location.pathname : a.getAttribute('href'));
		}));
		initScrollSpy();

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
		$('mobileSort').addEventListener('change', (ev) => {
			const [sort, dir] = ev.target.value.split(':');
			Object.assign(state.explorer, { sort, dir, page: 0 });
			setSortIndicators('logTable', sort, dir === 'asc');
			refreshLogs();
		});
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
			if (window.APP_USER && !window.APP_USER.admin) {
				toast('Read-only demo account: only the admin can pause the live stream');
				return;
			}
			try {
				renderSimulator(await api('/api/simulator/' + (state.simulatorRunning ? 'pause' : 'resume'), { method: 'POST' }));
				toast(state.simulatorRunning ? 'Live log stream resumed' : 'Live log stream paused');
			} catch (e) { toast('Failed: ' + e.message); }
		});
		$('incidentBtn').addEventListener('click', async () => {
			closeMenus();
			try {
				const s = await api('/api/simulator/incident?minutes=5', { method: 'POST' });
				renderSimulator(s);
				toast(`Incident injected into ${s.incidentService} for 5 minutes, watch the 15m/1h view`);
				refreshDashboard();
			} catch (e) { toast('Failed: ' + e.message); }
		});
	}

	// ==========================================================
	// WIDGET LAYOUT  (add / remove / reorder, saved per browser)
	// ==========================================================
	const WIDGETS = [
		{ id: 'kpi-total', group: 'KPIs', name: 'Total logs', desc: 'Events ingested and average rate', icon: 'Σ' },
		{ id: 'kpi-info', group: 'KPIs', name: 'Info events', desc: 'Informational volume and share', icon: 'i' },
		{ id: 'kpi-warn', group: 'KPIs', name: 'Warnings', desc: 'Warning volume and share', icon: '!' },
		{ id: 'kpi-error', group: 'KPIs', name: 'Errors', desc: 'Errors incl. fatal, and error share', icon: '✕' },
		{ id: 'kpi-throughput', group: 'KPIs', name: 'Throughput', desc: 'Average vs. peak events per minute', icon: '↗' },
		{ id: 'kpi-fatal', group: 'KPIs', name: 'Fatal events', desc: 'System-critical failures', icon: '★' },
		{ id: 'kpi-services', group: 'KPIs', name: 'Healthy services', desc: 'Services under the error threshold', icon: '▤' },
		{ id: 'kpi-incidents', group: 'KPIs', name: 'Open incidents', desc: 'Critical + high severity categories', icon: '⚠' },
		{ id: 'health', group: 'Charts', name: 'System health score', desc: 'Gauge weighted by errors and warnings', icon: '◔' },
		{ id: 'trend', group: 'Charts', name: 'Error trend timeline', desc: 'Errors and warnings over time', icon: '〜' },
		{ id: 'donut', group: 'Charts', name: 'Log level distribution', desc: 'Share of events per level', icon: '◕' },
		{ id: 'categories', group: 'Charts', name: 'Top error categories', desc: 'BFSI incident classes', icon: '≡' },
		{ id: 'volume', group: 'Charts', name: 'Event volume', desc: 'Stacked events per bucket by severity', icon: '▮' },
		{ id: 'errorrate', group: 'Charts', name: 'Error rate over time', desc: 'Error % per bucket against a 3% SLO', icon: '%' },
		{ id: 'svcerrors', group: 'Charts', name: 'Errors by service', desc: 'Error and warning counts per service', icon: '▥' },
		{ id: 'formats', group: 'Charts', name: 'Log format mix', desc: 'Formats detected by the universal parser', icon: '{}' },
		{ id: 'livefeed', group: 'Live & tables', name: 'Live log stream', desc: 'Real-time feed pushed from the database', icon: '●' },
		{ id: 'aianalysis', group: 'Live & tables', name: window.APP_USER && window.APP_USER.ai ? 'AI error analysis' : 'Error analysis', desc: window.APP_USER && window.APP_USER.ai ? 'Gemini root cause, impact and recovery steps' : 'NLP root cause, impact and recovery steps', icon: '✦' },
		{ id: 'incidents', group: 'Live & tables', name: 'Top critical incidents', desc: 'Categories ranked by severity', icon: '▲' },
		{ id: 'services', group: 'Live & tables', name: 'Service health', desc: 'Per-service volume and error rate', icon: '☰' },
		{ id: 'explorer', group: 'Live & tables', name: 'Log explorer', desc: 'Search, filter, sort and export logs', icon: '⌕' }
	];
	const DEFAULT_ORDER = ['kpi-total', 'kpi-info', 'kpi-warn', 'kpi-error', 'kpi-throughput', 'kpi-fatal', 'kpi-services', 'kpi-incidents',
		'health', 'trend', 'livefeed', 'donut', 'categories', 'volume', 'formats', 'errorrate', 'svcerrors', 'aianalysis', 'incidents', 'services', 'explorer'];
	const DEFAULT_HIDDEN = ['kpi-throughput', 'kpi-fatal', 'kpi-services', 'kpi-incidents', 'volume', 'formats', 'errorrate', 'svcerrors'];
	const LAYOUT_KEY = 'layout.v1';

	const layout = (() => {
		try {
			const saved = JSON.parse(load(LAYOUT_KEY, 'null'));
			if (saved && Array.isArray(saved.order)) {
				const known = new Set(DEFAULT_ORDER);
				const order = saved.order.filter((id) => known.has(id));
				DEFAULT_ORDER.forEach((id) => { if (!order.includes(id)) order.splice(DEFAULT_ORDER.indexOf(id), 0, id); });
				return { order, hidden: new Set((saved.hidden || []).filter((id) => known.has(id))) };
			}
		} catch (e) { /* fall through */ }
		return { order: [...DEFAULT_ORDER], hidden: new Set(DEFAULT_HIDDEN) };
	})();

	const widgetEl = (id) => document.querySelector(`[data-widget="${id}"]`);
	const widgetMeta = (id) => WIDGETS.find((w) => w.id === id);
	const visibleWidgets = () => layout.order.filter((id) => !layout.hidden.has(id));

	function saveLayout() {
		save(LAYOUT_KEY, JSON.stringify({ order: layout.order, hidden: [...layout.hidden] }));
	}

	function applyLayout() {
		const grid = document.querySelector('.grid');
		layout.order.forEach((id) => {
			const el = widgetEl(id);
			if (!el) return;
			grid.appendChild(el);
			el.classList.toggle('w-hidden', layout.hidden.has(id));
		});
		requestAnimationFrame(() => {
			document.querySelectorAll('[data-widget]:not(.w-hidden) canvas').forEach((cv) => {
				const ch = Chart.getChart(cv);
				if (ch) ch.resize();
			});
		});
	}

	function setWidgetVisible(id, visible) {
		visible ? layout.hidden.delete(id) : layout.hidden.add(id);
		saveLayout();
		applyLayout();
		renderWidgetList();
		if (visible) {
			const el = widgetEl(id);
			if (el && !document.body.classList.contains('editing')) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
		}
	}

	function moveWidget(id, delta) {
		const i = layout.order.indexOf(id), j = i + delta;
		if (j < 0 || j >= layout.order.length) return;
		[layout.order[i], layout.order[j]] = [layout.order[j], layout.order[i]];
		saveLayout();
		applyLayout();
		renderWidgetList();
	}

	function renderWidgetList() {
		const groups = ['KPIs', 'Charts', 'Live & tables'];
		const count = visibleWidgets().length;
		$('widgetList').innerHTML = `<div class="w-group" style="margin-top:12px">${count} of ${WIDGETS.length} widgets shown</div>` + groups.map((g) => {
			const ids = layout.order.filter((id) => widgetMeta(id) && widgetMeta(id).group === g);
			return `<div class="w-group">${g}</div>` + ids.map((id) => {
				const w = widgetMeta(id), on = !layout.hidden.has(id), idx = layout.order.indexOf(id);
				return `<div class="w-item${on ? '' : ' off'}" data-id="${id}">
					<div class="w-ico">${esc(w.icon)}</div>
					<div class="w-text"><div class="w-name">${esc(w.name)}</div><div class="w-desc">${esc(w.desc)}</div></div>
					<div class="w-move"><button data-move="-1" ${idx === 0 ? 'disabled' : ''} title="Move earlier" aria-label="Move ${esc(w.name)} up">▲</button><button data-move="1" ${idx === layout.order.length - 1 ? 'disabled' : ''} title="Move later" aria-label="Move ${esc(w.name)} down">▼</button></div>
					<label class="toggle" title="${on ? 'Remove from' : 'Add to'} dashboard"><input type="checkbox" ${on ? 'checked' : ''} aria-label="Show ${esc(w.name)}"><span></span></label>
				</div>`;
			}).join('');
		}).join('');
	}

	function initLayout() {
		document.querySelectorAll('[data-widget]').forEach((el) => {
			const b = document.createElement('button');
			b.className = 'w-remove';
			b.title = 'Remove widget';
			b.setAttribute('aria-label', 'Remove ' + (widgetMeta(el.dataset.widget) || {}).name);
			b.textContent = '✕';
			b.addEventListener('click', (ev) => {
				ev.stopPropagation();
				const id = el.dataset.widget;
				setWidgetVisible(id, false);
				toast(`Removed “${widgetMeta(id).name}”. Re-add it from Customize.`);
			});
			el.appendChild(b);
		});
		applyLayout();
		renderWidgetList();

		const open = () => { document.body.classList.add('editing'); $('drawer').setAttribute('aria-hidden', 'false'); renderWidgetList(); };
		const close = () => { document.body.classList.remove('editing'); $('drawer').setAttribute('aria-hidden', 'true'); };
		$('customizeBtn').addEventListener('click', () => document.body.classList.contains('editing') ? close() : open());
		$('drawerClose').addEventListener('click', close);
		$('drawerDone').addEventListener('click', close);
		$('drawerBackdrop').addEventListener('click', close);
		document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
		$('layoutReset').addEventListener('click', () => {
			layout.order = [...DEFAULT_ORDER];
			layout.hidden = new Set(DEFAULT_HIDDEN);
			saveLayout(); applyLayout(); renderWidgetList();
			toast('Layout reset to default');
		});
		$('layoutAll').addEventListener('click', () => {
			layout.hidden.clear();
			saveLayout(); applyLayout(); renderWidgetList();
		});
		$('widgetList').addEventListener('change', (ev) => {
			const item = ev.target.closest('.w-item');
			if (item && ev.target.type === 'checkbox') setWidgetVisible(item.dataset.id, ev.target.checked);
		});
		$('widgetList').addEventListener('click', (ev) => {
			const btn = ev.target.closest('button[data-move]');
			if (btn) moveWidget(btn.closest('.w-item').dataset.id, Number(btn.dataset.move));
		});
	}

	// ==========================================================
	// LIVE LOG STREAM  (Server-Sent Events from /api/logs/stream)
	// ==========================================================
	const RANK = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4, FATAL: 5 };
	const FEED_MAX = 300;
	const feed = { items: [], ids: new Set(), buffer: [], paused: false, minRank: 0, filter: '', open: new Set(), fresh: new Set(), es: null, connected: false, everConnected: false };

	function agoShort(ms) {
		const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
		if (s < 3) return 'just now';
		if (s < 60) return s + 's ago';
		if (s < 3600) return Math.floor(s / 60) + 'm ago';
		if (s < 86400) return Math.floor(s / 3600) + 'h ago';
		return Math.floor(s / 86400) + 'd ago';
	}

	function addToFeed(records, live) {
		const incoming = records.filter((r) => !feed.ids.has(r.id)).sort((a, b) => a.id - b.id);
		if (!incoming.length) return;
		const reading = $('feed').scrollTop > 40;
		if (live && (feed.paused || reading)) {
			incoming.forEach((r) => { feed.ids.add(r.id); feed.buffer.push(r); });
			renderFeed();
			return;
		}
		incoming.forEach((r) => {
			feed.ids.add(r.id);
			feed.items.unshift(r);
			if (live) feed.fresh.add(r.id);
		});
		trimFeed();
		renderFeed();
	}

	function flushBuffer() {
		feed.buffer.sort((a, b) => a.id - b.id).forEach((r) => { feed.items.unshift(r); feed.fresh.add(r.id); });
		feed.buffer = [];
		trimFeed();
		$('feed').scrollTop = 0;
		renderFeed();
	}

	function trimFeed() {
		feed.items.sort((a, b) => b.id - a.id); // newest stored first
		while (feed.items.length > FEED_MAX) {
			const r = feed.items.pop();
			feed.ids.delete(r.id);
		}
	}

	function feedVisible() {
		const q = feed.filter.toLowerCase();
		return feed.items.filter((r) => (RANK[r.level] || 0) >= feed.minRank
			&& (!q || [r.message, r.service, r.traceId, r.host, r.category, r.level].some((v) => v && v.toLowerCase().includes(q))));
	}

	function renderFeed() {
		const box = $('feed');
		const rows = feedVisible();
		let html = '';
		if (feed.buffer.length) {
			html += `<div class="feed-paused" id="feedFlush">${feed.paused ? '⏸ Stream paused' : '↑ New logs arrived'} · ${feed.buffer.length} new log${feed.buffer.length > 1 ? 's' : ''}, click to show</div>`;
		}
		let lastDay = null;
		const perDay = {};
		rows.forEach((r) => { const k = new Date(r.timestamp).toDateString(); perDay[k] = (perDay[k] || 0) + 1; });
		rows.forEach((r) => {
			const dayKey = new Date(r.timestamp).toDateString();
			if (dayKey !== lastDay) {
				lastDay = dayKey;
				const rel = dayLabel(r.timestamp);
				const cls = rel === 'Today' ? '' : rel === 'Yesterday' ? ' yesterday' : ' older';
				const longDate = new Date(r.timestamp).toLocaleDateString('en-US', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
				html += `<div class="feed-day"><span class="badge${cls}">${esc(rel || 'Earlier')}</span>${esc(longDate)}<span style="margin-left:auto;font-weight:600;color:var(--text-faint)">${perDay[dayKey]} shown</span></div>`;
			}
			const t = fmtLogTime(r.timestamp);
			const sev = r.level === 'FATAL' ? ' fat' : r.level === 'ERROR' ? ' err' : r.level === 'WARN' ? ' wrn' : '';
			html += `<div class="feed-row${sev}${feed.fresh.has(r.id) ? ' new' : ''}" data-id="${r.id}" title="Click for details">
				<div class="feed-time"><div class="t">${esc(t.time)}<small>${t.ms}</small></div><div class="d">${t.rel ? '<b>' + esc(t.rel) + '</b> · ' : ''}${esc(t.full)}</div></div>
				<div><span class="lvl L-${esc(r.level)}">${esc(r.level)}</span></div>
				<div class="feed-svc">${esc(r.service)}</div>
				<div class="feed-msg">${esc(r.message)}</div>
				<div class="feed-ago" data-ts="${r.timestamp}">${agoShort(r.timestamp)}</div>
			</div>${feed.open.has(r.id) ? feedDetail(r) : ''}`;
		});
		box.innerHTML = html || '<div class="empty">No logs match the stream filter yet.</div>';
		feed.fresh.clear();
		$('feedPause').textContent = feed.paused ? `Resume${feed.buffer.length ? ' (' + feed.buffer.length + ')' : ''}` : 'Pause';
	}

	function feedDetail(r) {
		const f = (k, v) => `<div><div class="k">${k}</div><div class="v">${v == null || v === '' ? '—' : esc(v)}</div></div>`;
		return `<div class="feed-detail"><div class="detail-grid">
			${f('When', new Date(r.timestamp).toLocaleString('en-US', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit' }))}
			${f('Format', r.formatLabel)}${f('Category', r.category)}${f('Trace ID', r.traceId)}${f('Host', r.host)}${f('Client IP', r.clientIp)}
		</div><div class="detail-actions">${analyzeButton(r)}</div><pre class="raw">${esc(r.rawLine)}</pre></div>`;
	}

	/** Opens the single-error analysis panel (handled by ai.js). Offered for every level; errors are emphasised. */
	function analyzeButton(r) {
		const strong = r.level === 'ERROR' || r.level === 'FATAL' || r.level === 'WARN';
		return `<button class="btn small ${strong ? 'dark' : 'ghost'}" data-analyze="${r.id}">✦ Analyze${strong && window.APP_USER && window.APP_USER.ai ? ' with AI' : ''}</button>`;
	}

	function setFeedStatus(kind) {
		const map = { ok: ['#10b981', 'Connected · real-time'], retry: ['#f59e0b', 'Reconnecting…'], off: ['#94a3b8', 'Disconnected'] };
		const [color, text] = map[kind];
		$('feedStatus').innerHTML = `<span class="dot" style="background:${color}${kind === 'ok' ? ';box-shadow:0 0 0 3px rgba(16,185,129,.2)' : ''}"></span>${text}`;
	}

	async function backfillFeed() {
		try {
			const page = await api('/api/logs?range=all&sort=id&dir=desc&size=80');
			addToFeed(page.content, false);
		} catch (e) { /* the stream will fill it */ }
	}

	function connectFeed() {
		if (!window.EventSource) { setFeedStatus('off'); return; }
		const es = new EventSource('/api/logs/stream');
		feed.es = es;
		es.addEventListener('open', () => {
			setFeedStatus('ok');
			if (feed.everConnected) backfillFeed(); // fill any gap after a reconnect
			feed.everConnected = true;
		});
		es.addEventListener('logs', (ev) => {
			try { addToFeed(JSON.parse(ev.data), true); } catch (e) { /* ignore malformed */ }
		});
		es.addEventListener('error', () => setFeedStatus(es.readyState === EventSource.CLOSED ? 'off' : 'retry'));
	}

	function initFeed() {
		$('feedLevel').addEventListener('change', (e) => { feed.minRank = Number(e.target.value); renderFeed(); });
		let t;
		$('feedFilter').addEventListener('input', (e) => { clearTimeout(t); t = setTimeout(() => { feed.filter = e.target.value.trim(); renderFeed(); }, 200); });
		$('feedPause').addEventListener('click', () => {
			feed.paused = !feed.paused;
			if (!feed.paused) flushBuffer(); else renderFeed();
		});
		$('feed').addEventListener('click', (ev) => {
			if (ev.target.closest('#feedFlush')) { feed.paused = false; flushBuffer(); return; }
			const row = ev.target.closest('.feed-row');
			if (!row) return;
			const id = Number(row.dataset.id);
			feed.open.has(id) ? feed.open.delete(id) : feed.open.add(id);
			renderFeed();
		});
		$('feed').addEventListener('scroll', () => { if ($('feed').scrollTop <= 40 && feed.buffer.length && !feed.paused) flushBuffer(); });
		setInterval(() => {
			document.querySelectorAll('.feed-ago').forEach((el) => { el.textContent = agoShort(Number(el.dataset.ts)); });
			const cutoff = Date.now() - 60000;
			const perMin = feed.items.filter((r) => r.timestamp >= cutoff).length + feed.buffer.filter((r) => r.timestamp >= cutoff).length;
			$('feedRate').textContent = `${perMin} events/min`;
		}, 1000);
		backfillFeed().then(connectFeed);
	}

	// ==========================================================
	// DEMO TOUR (demo mode only)
	// ==========================================================
	function initTour() {
		const box = $('demoTour');
		if (!box) return;
		let st = { dismissed: false, done: [] };
		try { st = Object.assign(st, JSON.parse(load('tour', '{}'))); } catch (e) { /* defaults */ }
		const persist = () => save('tour', JSON.stringify(st));
		const mark = (step) => {
			if (!st.done.includes(step)) st.done.push(step);
			persist();
			box.querySelectorAll('[data-tour]').forEach((b) => b.closest('li').classList.toggle('done', st.done.includes(b.dataset.tour)));
		};
		box.hidden = st.dismissed;
		mark('__init');
		$('tourClose').addEventListener('click', () => { st.dismissed = true; persist(); box.hidden = true; });
		box.addEventListener('click', async (ev) => {
			const b = ev.target.closest('[data-tour]');
			if (!b) return;
			const step = b.dataset.tour;
			const show = (id) => { if (layout.hidden.has(id)) setWidgetVisible(id, true); widgetEl(id).scrollIntoView({ behavior: 'smooth', block: 'start' }); };
			if (step === 'incident') {
				try {
					renderSimulator(await api('/api/simulator/incident?service=upi-switch&minutes=5', { method: 'POST' }));
					toast('UPI switch incident started for 5 minutes. Give it about a minute, then do step 2.');
				} catch (e) { toast('Failed: ' + e.message); }
			}
			if (step === 'range') document.querySelector('#rangeMenu [data-range="15m"]').click();
			if (step === 'ai') { show('aianalysis'); setTimeout(() => $('aiRun').click(), 400); }
			if (step === 'pdf') $('pdfBtn').click();
			if (step === 'log') {
				show('explorer');
				filterExplorer({ levels: ['ERROR', 'FATAL'] });
				try {
					const page = await api('/api/logs?' + explorerParams({ page: 0, size: 1 }));
					if (page.content.length) {
						const t = document.createElement('button');
						t.hidden = true;
						t.dataset.analyze = page.content[0].id;
						document.body.appendChild(t);
						t.click(); // opens the single-error panel (ai.js)
						t.remove();
					} else {
						toast('No errors yet: simulate an incident first');
					}
				} catch (e) { /* ignore */ }
			}
			mark(step);
		});
	}

	// shared with report.js (PDF export)
	window.LA = {
		get summary() { return state.summary; }, get range() { return state.range; }, explorer: state.explorer,
		feed, WIDGETS, visibleWidgets, widgetEl, widgetMeta, explorerParams, api, toast, dayLabel, fmtLogTime, bucketRange, filterExplorer, esc, agoShort, ago,
		RANGE_LABEL, delta, charts: { trend: trendChart, donut: donutChart, categories: barChart, volume: volumeChart, formats: formatChart, errorrate: errorRateChart, svcerrors: svcErrorChart }
	};

	// ==========================================================
	// BOOT
	// ==========================================================
	initEvents();
	initLayout();
	initFeed();
	initTour();
	syncExplorerControls();
	// ---------- loading screen ----------
	const loader = (() => {
		const started = performance.now();
		const set = (pct, text) => {
			if ($('loaderBar')) $('loaderBar').style.width = pct + '%';
			if (text && $('loaderText')) $('loaderText').textContent = text;
		};
		const done = () => {
			const el = $('appLoader');
			if (!el || el.classList.contains('done')) return;
			set(100, 'Ready');
			// stay long enough to be seen, never long enough to annoy
			setTimeout(() => { el.classList.add('done'); setTimeout(() => el.remove(), 600); }, Math.max(0, 900 - (performance.now() - started)));
		};
		return { set, done };
	})();
	window.LA_LOADER = loader;
	setTimeout(loader.done, 12000); // safety net

	applyChartTheme();
	loader.set(15, 'Connecting to the log store…');
	refreshFacets();
	(async () => {
		let ok = await refreshDashboard();
		while (!ok) {
			loader.set(15, 'Cannot reach the server – retrying…');
			await new Promise((r) => setTimeout(r, 3000));
			ok = await refreshDashboard();
		}
		loader.set(65, 'Computing trends and health score…');
		await refreshLogs();
		loader.set(85, 'Opening the live log stream…');
		setTimeout(loader.done, 250);
	})();

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
