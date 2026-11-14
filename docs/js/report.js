/* Log Analyzer – multi-page PDF report of the current dashboard (jsPDF + AutoTable).
 * Charts are re-rendered off-screen at print-specific sizes, so they keep good proportions
 * whatever the browser window looked like. */
(function () {
	'use strict';

	const LIBS = [
		'https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js',
		'https://cdnjs.cloudflare.com/ajax/libs/jspdf-autotable/3.8.2/jspdf.plugin.autotable.min.js'
	];

	// ---------- palette (RGB) ----------
	const NAVY = [15, 23, 42], INK = [30, 41, 59], SLATE = [71, 85, 105], FAINT = [124, 135, 154], LINE = [226, 232, 240];
	const PAGE_BG = [244, 246, 251], WHITE = [255, 255, 255], SHADOW = [229, 233, 242];
	const INDIGO = [79, 70, 229], CYAN = [6, 182, 212];
	const GOOD = [4, 120, 87], BAD = [190, 18, 60], NEUTRAL = [67, 56, 202];
	const SEV = { CRITICAL: [225, 29, 72], HIGH: [239, 68, 68], MEDIUM: [217, 119, 6], DEGRADED: [217, 119, 6], LOW: [37, 99, 235], HEALTHY: [5, 150, 105] };
	const STAT = { BREACHED: [225, 29, 72], AT_RISK: [217, 119, 6], MET: [5, 150, 105], OPEN: [225, 29, 72], ACKNOWLEDGED: [217, 119, 6], RESOLVED: [5, 150, 105] };
	const LVL = { FATAL: [159, 18, 57], ERROR: [239, 68, 68], WARN: [217, 119, 6], INFO: [16, 185, 129], DEBUG: [100, 116, 139], TRACE: [100, 116, 139] };
	const FORMAT_HEX = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#14b8a6', '#f97316', '#ec4899', '#84cc16', '#64748b', '#a855f7'];
	const hexRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));

	// ---------- page geometry (mm) ----------
	const W = 210, H = 297, M = 14, CW = W - 2 * M, GAP = 6, TOP = 24, BOTTOM = H - 17;
	const HALF = (CW - GAP) / 2;
	const PX_PER_MM = 5; // off-screen chart canvas scale; fonts below are in these px

	const nf = new Intl.NumberFormat('en-US');
	const pct = (v) => (v < 10 ? v.toFixed(2) : v.toFixed(1)) + '%';
	const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'local time';
	const fmtDT = (ms) => new Date(ms).toLocaleString('en-US', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });

	/** Standard PDF fonts only cover Latin-1, so map or drop anything else. */
	function t(s) {
		if (s == null) return '';
		return String(s)
			.replace(/[–—]/g, '-').replace(/…/g, '...').replace(/[‘’]/g, "'").replace(/[“”]/g, '"')
			.replace(/→/g, '->').replace(/₹\s?/g, 'Rs ').replace(/≥/g, '>=').replace(/⏎/g, ' ').replace(/[^\x09\x0A\x0D\x20-\xFF]/g, '');
	}

	function loadScript(src) {
		return new Promise((resolve, reject) => {
			const s = document.createElement('script');
			s.src = src;
			s.onload = resolve;
			s.onerror = () => reject(new Error('Could not load ' + src));
			document.head.appendChild(s);
		});
	}

	async function ensureLibs() {
		const libs = window.APP_PDF_LIBS || LIBS; // the static demo serves its own copies
		if (!(window.jspdf && window.jspdf.jsPDF)) await loadScript(libs[0]);
		if (!window.jspdf.jsPDF.API.autoTable) await loadScript(libs[1]);
	}

	function overlay(show, text) {
		let o = document.getElementById('pdfOverlay');
		if (!o) {
			o = document.createElement('div');
			o.id = 'pdfOverlay';
			o.className = 'overlay';
			o.innerHTML = '<div class="overlay-box"><div class="spinner"></div><div class="card-title" id="pdfOverlayText">Building report…</div><div class="card-sub" style="margin-top:6px">Rendering print-quality charts</div></div>';
			document.body.appendChild(o);
		}
		if (text) document.getElementById('pdfOverlayText').textContent = text;
		o.classList.toggle('show', show);
	}

	// =====================================================================
	// PRINT CHART RENDERER
	// =====================================================================
	const AXIS_X = { grid: { display: false }, border: { display: false }, ticks: { color: '#64748b', font: { size: 12 }, maxRotation: 0, autoSkip: true, autoSkipPadding: 16, padding: 6 } };
	const AXIS_Y = { beginAtZero: true, grid: { color: '#edf1f7', drawTicks: false }, border: { display: false }, ticks: { color: '#94a3b8', font: { size: 12 }, padding: 10, precision: 0, maxTicksLimit: 6 } };
	const clone = (o) => JSON.parse(JSON.stringify(o));

	function printOptions(key) {
		const base = {
			responsive: false, maintainAspectRatio: false, animation: false, devicePixelRatio: 3,
			layout: { padding: { top: 8, right: 14, bottom: 4, left: 4 } },
			plugins: { legend: { display: false }, tooltip: { enabled: false } },
			scales: { x: clone(AXIS_X), y: clone(AXIS_Y) }
		};
		switch (key) {
			case 'volume':
				base.scales.x.stacked = true;
				base.scales.y.stacked = true;
				break;
			case 'errorrate':
				base.scales.y.ticks.callback = (v) => v + '%';
				base.scales.y.ticks.precision = undefined;
				break;
			case 'svcerrors':
				base.scales.x.ticks = Object.assign(base.scales.x.ticks, { maxRotation: 50, minRotation: 40, autoSkip: false, font: { size: 11 }, color: '#334155' });
				base.plugins.legend = { display: true, position: 'top', align: 'end', labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true, font: { size: 12 }, color: '#475569', padding: 14 } };
				break;
			case 'categories':
				base.indexAxis = 'y';
				base.scales = {
					x: Object.assign(clone(AXIS_Y), { grid: { color: '#edf1f7', drawTicks: false } }),
					y: { grid: { display: false }, border: { display: false }, ticks: { color: '#334155', font: { size: 13 }, padding: 10, autoSkip: false } }
				};
				break;
			case 'donut':
			case 'formats':
				delete base.scales;
				base.cutout = '68%';
				base.layout.padding = 6;
				break;
			default:
				break;
		}
		return base;
	}

	/** Renders a dashboard chart into an off-screen canvas of exactly wMm x hMm. */
	function printChart(key, wMm, hMm) {
		const live = window.LA.charts[key];
		const holder = document.createElement('div');
		holder.style.cssText = 'position:fixed;left:-10000px;top:0;';
		const canvas = document.createElement('canvas');
		const wPx = Math.round(wMm * PX_PER_MM), hPx = Math.round(hMm * PX_PER_MM);
		canvas.style.width = wPx + 'px';
		canvas.style.height = hPx + 'px';
		canvas.width = wPx;
		canvas.height = hPx;
		holder.appendChild(canvas);
		document.body.appendChild(holder);
		const data = {
			labels: live.data.labels.slice(),
			datasets: live.data.datasets.map((ds) => Object.assign({}, ds, {
				data: ds.data.slice(),
				borderWidth: ds.type === 'bar' || live.config.type === 'bar' ? 0 : (ds.borderWidth || 2) * 1.1,
				maxBarThickness: ds.maxBarThickness ? ds.maxBarThickness * 1.2 : undefined
			}))
		};
		if (live.config.type === 'doughnut') data.datasets.forEach((ds) => { ds.borderWidth = 3; ds.hoverOffset = 0; ds.borderColor = '#ffffff'; }); // print is always light
		const chart = new Chart(canvas.getContext('2d'), { type: live.config.type, data, options: printOptions(key) });
		const url = chart.toBase64Image('image/png', 1);
		chart.destroy();
		holder.remove();
		return url;
	}

	// =====================================================================
	// REPORT BUILDER
	// =====================================================================
	async function buildReport() {
		const LA = window.LA, s = LA.summary;
		if (!s) throw new Error('Dashboard data not loaded yet');
		const BRAND = (window.APP_BRAND && window.APP_BRAND.name) || 'NovaBank';
		const TAGLINE = (window.APP_BRAND && window.APP_BRAND.tagline) || 'BFSI Observability';
		const { jsPDF } = window.jspdf;
		const doc = new jsPDF({ unit: 'mm', format: 'a4', compress: true });
		doc.setProperties({ title: `${BRAND} Observability Report`, subject: 'Log analytics and incident report', author: 'Log Analyzer', creator: 'Log Analyzer' });

		const generated = Date.now();
		const biz = LA.business ? await LA.business.forReport().catch(() => null) : null;
		const B = LA.business;
		const rangeLabel = LA.RANGE_LABEL[s.range] || s.range;
		const period = `${fmtDT(s.from)}  to  ${fmtDT(s.to)}`;
		const tot = s.totals, prev = s.previous, errors = tot.error + tot.fatal;
		const healthy = s.services.filter((x) => x.status === 'HEALTHY').length;
		const status = s.healthScore >= 85 ? 'HEALTHY' : s.healthScore >= 60 ? 'DEGRADED' : 'CRITICAL';
		const statusColor = SEV[status];
		const prevLabel = 'vs prev ' + (s.range === '7d' ? '7d' : s.range);

		// ---------------- drawing helpers ----------------
		const painted = new Set();
		const paintBg = () => {
			const n = doc.getCurrentPageInfo().pageNumber;
			if (painted.has(n)) return;
			painted.add(n);
			doc.setFillColor(...PAGE_BG);
			doc.rect(0, 0, W, H, 'F');
		};
		let y = TOP;
		const toc = [];
		const newPage = () => { doc.addPage(); paintBg(); y = TOP + 2; };
		const ensure = (h) => { if (y + h > BOTTOM) newPage(); };

		const text = (str, x, yy, o) => {
			o = o || {};
			doc.setFont('helvetica', o.bold ? 'bold' : 'normal');
			doc.setFontSize(o.size || 10);
			doc.setTextColor(...(o.color || NAVY));
			doc.text(Array.isArray(str) ? str.map(t) : t(str), x, yy, { align: o.align || 'left', maxWidth: o.maxWidth, baseline: o.baseline || 'alphabetic' });
		};
		const textW = (str, size, bold) => { doc.setFont('helvetica', bold ? 'bold' : 'normal'); doc.setFontSize(size); return doc.getTextWidth(t(str)); };
		const rr = (x, yy, w, h, r, fill, stroke, lw) => {
			if (fill) doc.setFillColor(...fill);
			if (stroke) { doc.setDrawColor(...stroke); doc.setLineWidth(lw || 0.25); }
			doc.roundedRect(x, yy, w, h, r, r, fill && stroke ? 'FD' : fill ? 'F' : 'S');
		};
		const tint = (rgb, a) => rgb.map((c) => Math.round(255 - (255 - c) * a));
		const alpha = (a, fn) => { doc.setGState(new doc.GState({ opacity: a })); fn(); doc.setGState(new doc.GState({ opacity: 1 })); };

		/** White card with a soft drop shadow. */
		function card(x, yy, w, h) {
			rr(x + 0.3, yy + 0.8, w, h, 3.2, SHADOW);
			rr(x, yy, w, h, 3.2, WHITE, LINE, 0.2);
		}
		/** Card title block; returns the y where content can start. */
		function cardHead(x, yy, w, title, sub, chip) {
			text(title, x + 6, yy + 8.5, { size: 10.5, bold: true });
			if (sub) text(sub, x + 6, yy + 13.2, { size: 7.4, color: FAINT, maxWidth: w - 12 - (chip ? textW(chip.text, 7, true) + 10 : 0) });
			if (chip) {
				const cw = textW(chip.text, 7, true) + 7;
				rr(x + w - 6 - cw, yy + 4.5, cw, 5.6, 2.8, tint(chip.color, 0.12));
				text(chip.text, x + w - 6 - cw / 2, yy + 8.3, { size: 7, bold: true, color: chip.color, align: 'center' });
			}
			return yy + (sub ? 18 : 14);
		}
		function sectionTitle(title, sub) {
			toc.push({ title, page: doc.getNumberOfPages() });
			doc.setFillColor(...INDIGO);
			doc.roundedRect(M, y - 4.4, 1.5, 6.4, 0.7, 0.7, 'F');
			text(title, M + 4.5, y, { size: 13, bold: true });
			if (sub) text(sub, M + 4.5, y + 5.2, { size: 8, color: FAINT, maxWidth: CW - 6 });
			y += sub ? 10.5 : 7.5;
		}
		const tocEntry = (title) => toc.push({ title, page: doc.getNumberOfPages() });

		/** Arc as a smooth polyline; angles in degrees, 0 = 12 o'clock, clockwise. */
		function arc(cx, cy, r, a0, a1, color, width) {
			if (a1 - a0 < 0.5) return;
			const steps = Math.max(8, Math.ceil((a1 - a0) / 3));
			const pt = (a) => [cx + r * Math.sin(a * Math.PI / 180), cy - r * Math.cos(a * Math.PI / 180)];
			const [sx, sy] = pt(a0);
			const segs = [];
			let [px, py] = [sx, sy];
			for (let i = 1; i <= steps; i++) {
				const [nx, ny] = pt(a0 + (a1 - a0) * i / steps);
				segs.push([nx - px, ny - py]);
				px = nx; py = ny;
			}
			doc.setDrawColor(...color);
			doc.setLineWidth(width);
			doc.setLineCap('round');
			doc.lines(segs, sx, sy, [1, 1], 'S', false);
			doc.setLineCap('butt');
		}

		function ringGauge(cx, cy, r, score, color) {
			arc(cx, cy, r, 0, 359.9, [232, 236, 243], 4.2);
			arc(cx, cy, r, 0, Math.max(2, 360 * score / 100), color, 4.2);
			text(score.toFixed(1), cx, cy + 2.2, { size: 20, bold: true, color, align: 'center' });
			text('of 100', cx, cy + 7, { size: 7, color: FAINT, align: 'center' });
		}

		/** Mini trend line with a soft area fill. */
		function sparkline(values, x, yy, w, h, color) {
			const v = values.filter((n) => Number.isFinite(n));
			if (v.length < 3) return;
			const max = Math.max(...v), min = Math.min(...v), span = max - min || 1;
			const pts = v.map((n, i) => [x + w * i / (v.length - 1), yy + h - (n - min) / span * h]);
			const rel = pts.slice(1).map((p, i) => [p[0] - pts[i][0], p[1] - pts[i][1]]);
			alpha(0.13, () => {
				doc.setFillColor(...color);
				const area = rel.concat([[0, yy + h - pts[pts.length - 1][1]], [x - pts[pts.length - 1][0], 0]]);
				doc.lines(area, pts[0][0], pts[0][1], [1, 1], 'F', true);
			});
			doc.setDrawColor(...color);
			doc.setLineWidth(0.45);
			doc.setLineJoin('round');
			doc.lines(rel, pts[0][0], pts[0][1], [1, 1], 'S', false);
			const last = pts[pts.length - 1];
			doc.setFillColor(...color);
			doc.circle(last[0], last[1], 0.7, 'F');
		}

		/** Up/down arrow triangle. */
		function arrow(x, yy, dir, color) {
			doc.setFillColor(...color);
			if (dir === 'up') doc.triangle(x, yy + 1.6, x + 2.2, yy + 1.6, x + 1.1, yy - 0.3, 'F');
			else if (dir === 'down') doc.triangle(x, yy - 0.3, x + 2.2, yy - 0.3, x + 1.1, yy + 1.6, 'F');
			else doc.rect(x + 0.3, yy + 0.4, 1.6, 0.8, 'F');
		}

		// ---------------- KPI definitions ----------------
		const minutes = Math.max(1, (s.to - s.from) / 60000);
		const peakPerMin = Math.max(0, ...s.trend.map((p) => p.total)) / s.bucketMinutes;
		const openIncidents = s.categories.filter((c) => c.severity === 'CRITICAL' || c.severity === 'HIGH');
		const series = {
			total: s.trend.map((p) => p.total), info: s.trend.map((p) => p.total - p.warnings - p.errors),
			warn: s.trend.map((p) => p.warnings), error: s.trend.map((p) => p.errors),
			rate: s.trend.map((p) => (p.total ? p.errors * 100 / p.total : 0))
		};
		const pv = (k) => (prev ? prev[k] : null);
		const KPI = {
			'kpi-total': { label: 'Total logs', value: nf.format(tot.total), ctx: (tot.total / minutes).toFixed(1) + '/min', color: [99, 102, 241], icon: 'lines', spark: series.total, delta: LA.delta(tot.total, pv('total'), null) },
			'kpi-info': { label: 'Info', value: nf.format(tot.info), ctx: pct(tot.total ? tot.info * 100 / tot.total : 0) + ' of vol.', color: [16, 185, 129], icon: 'info', spark: series.info, delta: LA.delta(tot.info, pv('info'), null) },
			'kpi-warn': { label: 'Warnings', value: nf.format(tot.warn), ctx: pct(s.warnRate) + ' of vol.', color: [217, 119, 6], icon: 'warn', spark: series.warn, delta: LA.delta(tot.warn, pv('warn'), true) },
			'kpi-error': { label: 'Errors', value: nf.format(errors), ctx: pct(s.errorRate) + ' rate', color: [239, 68, 68], icon: 'x', spark: series.error, delta: LA.delta(errors, prev ? prev.error + prev.fatal : null, true) },
			'kpi-throughput': { label: 'Throughput', value: (tot.total / minutes).toFixed(1) + '/min', ctx: 'peak ' + peakPerMin.toFixed(1), color: [14, 165, 233], icon: 'zig', spark: series.total, delta: LA.delta(tot.total, pv('total'), null) },
			'kpi-fatal': { label: 'Fatal events', value: nf.format(tot.fatal), ctx: 'critical failures', color: [159, 18, 57], icon: 'bang', spark: series.error, delta: LA.delta(tot.fatal, pv('fatal'), true) },
			'kpi-services': { label: 'Healthy services', value: `${healthy}/${s.services.length}`, ctx: s.services.length ? Math.round(healthy * 100 / s.services.length) + '% healthy' : '-', color: [5, 150, 105], icon: 'stack', spark: series.rate, delta: null },
			'kpi-incidents': { label: 'Open incidents', value: String(openIncidents.length), ctx: openIncidents.length ? openIncidents[0].severity.toLowerCase() : 'none open', color: [225, 29, 72], icon: 'warn', spark: series.error, delta: null }
		};

		function kpiIcon(kind, x, yy, color) {
			rr(x, yy, 7, 7, 2, tint(color, 0.14));
			doc.setDrawColor(...color); doc.setFillColor(...color); doc.setLineWidth(0.5); doc.setLineCap('round');
			const cx = x + 3.5, cy = yy + 3.5;
			switch (kind) {
				case 'lines': [-1.3, 0, 1.3].forEach((d, i) => doc.line(cx - 1.8, cy + d, cx + (i === 2 ? 0.6 : 1.8), cy + d)); break;
				case 'info': doc.circle(cx, cy - 1.5, 0.35, 'F'); doc.line(cx, cy - 0.3, cx, cy + 1.8); break;
				case 'warn': doc.setLineJoin('round'); doc.triangle(cx, cy - 2, cx - 2.1, cy + 1.7, cx + 2.1, cy + 1.7, 'S'); doc.line(cx, cy - 0.5, cx, cy + 0.5); break;
				case 'x': doc.line(cx - 1.5, cy - 1.5, cx + 1.5, cy + 1.5); doc.line(cx + 1.5, cy - 1.5, cx - 1.5, cy + 1.5); break;
				case 'zig': doc.lines([[1.2, -1.3], [1, 1], [1.6, -2]], cx - 1.9, cy + 1.2, [1, 1], 'S', false); break;
				case 'bang': doc.circle(cx, cy, 2, 'S'); doc.line(cx, cy - 1, cx, cy + 0.2); doc.circle(cx, cy + 1, 0.25, 'F'); break;
				case 'stack': doc.roundedRect(cx - 1.9, cy - 1.8, 3.8, 1.5, 0.4, 0.4, 'S'); doc.roundedRect(cx - 1.9, cy + 0.4, 3.8, 1.5, 0.4, 0.4, 'S'); break;
				default: break;
			}
			doc.setLineCap('butt');
		}

		function kpiTile(id, x, yy, w, h) {
			const k = KPI[id];
			card(x, yy, w, h);
			kpiIcon(k.icon, x + 4.5, yy + 4.2, k.color);
			text(k.label.toUpperCase(), x + 13.5, yy + 8.9, { size: 6.6, bold: true, color: SLATE, maxWidth: w - 16 });
			const vs = w > 50 ? 19 : 16;
			text(k.value, x + 4.5, yy + 19.5, { size: vs, bold: true });
			// delta pill (or context text when there is no previous period)
			const py = yy + 22.6;
			if (k.delta) {
				const col = k.delta.cls === 'good' ? GOOD : k.delta.cls === 'bad' ? BAD : NEUTRAL;
				const label = Math.abs(k.delta.pct).toFixed(1) + '%';
				const pw = textW(label, 7, true) + 7.6;
				rr(x + 4.5, py, pw, 4.8, 2.4, tint(col, 0.12));
				arrow(x + 6.1, py + 1.6, k.delta.dir, col);
				text(label, x + 9.2, py + 3.5, { size: 7, bold: true, color: col });
				text(w > 50 ? prevLabel + ' · ' + k.ctx : k.ctx, x + 4.5 + pw + 2, py + 3.5, { size: 6.4, color: FAINT, maxWidth: w - pw - 10 });
			} else {
				text(k.ctx, x + 4.5, py + 3.5, { size: 7, bold: true, color: k.color, maxWidth: w - 9 });
			}
			const sy = py + 6.4; // keep the sparkline clear of the delta row
			sparkline(k.spark, x + 4.5, sy, w - 9, Math.max(3, yy + h - 3.6 - sy), k.color);
		}

		function kpiGrid(ids, x0, yy, cols, w, h) {
			ids.forEach((id, i) => kpiTile(id, x0 + (i % cols) * (w + 4), yy + Math.floor(i / cols) * (h + 4), w, h));
			return yy + Math.ceil(ids.length / cols) * (h + 4) - 4;
		}

		// ---------------- widget renderers ----------------
		const bucketText = s.bucketMinutes >= 1440 ? 'day' : s.bucketMinutes >= 60 ? (s.bucketMinutes === 60 ? 'hour' : s.bucketMinutes / 60 + ' hours') : s.bucketMinutes + ' minutes';
		const errs = s.trend.map((p) => p.errors), peakIdx = errs.indexOf(Math.max(...errs));
		const breaches = s.trend.filter((p) => p.total && p.errors * 100 / p.total > 3).length;

		const CHART_META = {
			trend: { title: 'Error trend timeline', sub: `Errors (solid) and warnings (dashed) per ${bucketText} · ${tz}`, chip: peakIdx >= 0 && errs[peakIdx] ? { text: 'Peak ' + new Date(s.trend[peakIdx].t).toLocaleTimeString('en-US', { hour: 'numeric', minute: s.bucketMinutes < 60 ? '2-digit' : undefined }) + ' · ' + errs[peakIdx] + ' errors', color: SEV.HIGH } : null },
			volume: { title: 'Event volume', sub: `Events per ${bucketText}: info/debug (green), warnings (amber), errors (red)` },
			categories: { title: 'Top error categories', sub: 'BFSI incident classes ranked by error count', chip: { text: s.categories.length + ' categories', color: INDIGO } },
			errorrate: { title: 'Error rate over time', sub: 'Error % per bucket · dashed line = 3% SLO', chip: { text: breaches ? breaches + ' SLO breach' + (breaches > 1 ? 'es' : '') : 'Within SLO', color: breaches ? BAD : GOOD } },
			svcerrors: { title: 'Errors by service', sub: 'Errors and warnings per service' }
		};

		/** Line/bar chart inside a card; w is the card width. */
		function chartCard(key, x, yy, w, h) {
			const meta = CHART_META[key];
			card(x, yy, w, h);
			const cy = cardHead(x, yy, w, meta.title, meta.sub, meta.chip);
			const cw = w - 8, ch = yy + h - cy - 4;
			doc.addImage(printChart(key, cw, ch), 'PNG', x + 4, cy, cw, ch, undefined, 'FAST');
		}

		function donutCard(key, x, yy, w, h) {
			const isLevels = key === 'donut';
			const rows = isLevels
				? [['FATAL', tot.fatal], ['ERROR', tot.error], ['WARN', tot.warn], ['INFO', tot.info], ['DEBUG', tot.debug + tot.trace]].map(([l, v]) => ({ name: l, v, color: LVL[l] }))
				: (s.formats || []).map((f, i) => ({ name: f.label, v: f.count, color: hexRgb(FORMAT_HEX[i % FORMAT_HEX.length]) }));
			card(x, yy, w, h);
			const cy = cardHead(x, yy, w, isLevels ? 'Log level distribution' : 'Log format mix', isLevels ? 'Share of events by severity' : 'Formats detected by the universal parser');
			const wide = w > HALF + 1;
			const size = wide ? Math.min(h - 26, 62) : Math.min(w - 44, 40);
			const dx = wide ? x + 10 : x + (w - size) / 2;
			doc.addImage(printChart(key, size, size), 'PNG', dx, cy, size, size, undefined, 'FAST');
			text(nf.format(isLevels ? tot.total : rows.length), dx + size / 2, cy + size / 2 + 1.5, { size: wide ? 15 : 12, bold: true, align: 'center' });
			text(isLevels ? 'EVENTS' : 'FORMATS', dx + size / 2, cy + size / 2 + 5.5, { size: 6, bold: true, color: FAINT, align: 'center' });
			// legend
			const lx = wide ? dx + size + 14 : x + 7, lw = wide ? x + w - 8 - lx : w - 14;
			let ly = wide ? cy + 6 : cy + size + 6;
			const maxRows = wide ? 9 : Math.floor((yy + h - 5 - ly) / 4.9);
			rows.slice(0, maxRows).forEach((r) => {
				rr(lx, ly - 2.4, 2.6, 2.6, 0.6, r.color);
				text(r.name, lx + 4.5, ly, { size: 7.6, color: SLATE, maxWidth: lw - 30 });
				text(nf.format(r.v), lx + lw - 13, ly, { size: 7.6, bold: true, align: 'right' });
				text(pct(tot.total ? r.v * 100 / tot.total : 0), lx + lw, ly, { size: 7, color: FAINT, align: 'right' });
				ly += 4.9;
			});
		}

		function statBox(bx, by, bw, bh, k, v, c) {
			rr(bx, by, bw, bh, 2.2, [248, 250, 252], LINE, 0.2);
			text(k, bx + 3.5, by + 4.8, { size: 6.2, bold: true, color: FAINT });
			text(v, bx + 3.5, by + bh - 3, { size: bh > 13 ? 12 : 10.5, bold: true, color: c });
		}

		function statusPill(cx, yy) {
			const sw = textW(status, 7.4, true) + 8;
			rr(cx - sw / 2, yy, sw, 5.8, 2.9, tint(statusColor, 0.14));
			text(status, cx, yy + 4, { size: 7.4, bold: true, color: statusColor, align: 'center' });
		}

		/** Three layouts: compact (cover), half-width card, full-width horizontal card. */
		function healthCard(x, yy, w, h) {
			card(x, yy, w, h);
			const rates = [['ERROR RATE', pct(s.errorRate), SEV.HIGH], ['WARN RATE', pct(s.warnRate), [217, 119, 6]]];
			if (w < 70) {
				text('HEALTH INDEX', x + w / 2, yy + 8, { size: 7, bold: true, color: SLATE, align: 'center' });
				const r = 14;
				ringGauge(x + w / 2, yy + 13 + r + 3, r, s.healthScore, statusColor);
				statusPill(x + w / 2, yy + 13 + 2 * r + 9);
				const bw = (w - 13) / 2, by = yy + h - 17;
				rates.forEach(([k, v, c], i) => statBox(x + 5 + i * (bw + 3), by, bw, 12.5, k.replace(' RATE', ''), v, c));
				return;
			}
			if (w > HALF + 1) {
				const cy = cardHead(x, yy, w, 'System health score', 'Health = 100 - (error % x 5 + warning %), clamped to 0-100', { text: status, color: statusColor });
				const r = Math.min(16, (yy + h - cy - 8) / 2);
				ringGauge(x + 10 + r, cy + r + 2, r, s.healthScore, statusColor);
				const sx = x + 2 * r + 24, sw = x + w - 6 - sx, bw = (sw - 8) / 3;
				[...rates, ['ERRORS / WARNINGS', `${nf.format(errors)} / ${nf.format(tot.warn)}`, INK]].forEach(([k, v, c], i) => statBox(sx + i * (bw + 4), cy + 1, bw, 15, k, v, c));
				// score scale with thresholds
				const by = cy + 24;
				rr(sx, by, sw, 3, 1.5, [232, 236, 243]);
				rr(sx, by, Math.max(3, sw * s.healthScore / 100), 3, 1.5, statusColor);
				[[60, 'Degraded < 60'], [85, 'Healthy >= 85']].forEach(([v, l]) => {
					doc.setDrawColor(...FAINT); doc.setLineWidth(0.25);
					doc.line(sx + sw * v / 100, by - 1.2, sx + sw * v / 100, by + 4.2);
					text(l, sx + sw * v / 100, by + 8, { size: 6.4, color: FAINT, align: 'center' });
				});
				return;
			}
			const cy = cardHead(x, yy, w, 'System health score', '100 - (error % x 5 + warning %)', { text: status, color: statusColor });
			const r = Math.min(17, (h - 44) / 2);
			ringGauge(x + w / 2, cy + r + 4, r, s.healthScore, statusColor);
			const bw = (w - 16) / 2;
			rates.forEach(([k, v, c], i) => statBox(x + 6 + i * (bw + 4), yy + h - 21, bw, 15, k, v, c));
		}

		const HALF_H = 92, FULL_CHART_H = 96;
		const isHalf = (id) => ['errorrate', 'svcerrors', 'donut', 'formats', 'health'].includes(id);
		const TITLES = { health: 'System health', trend: 'Error trend timeline', volume: 'Event volume', errorrate: 'Error rate over time', svcerrors: 'Errors by service', donut: 'Log level distribution', formats: 'Log format mix' };

		function drawWidget(id, x, yy, w, h) {
			if (id === 'health') healthCard(x, yy, w, h);
			else if (id === 'donut' || id === 'formats') donutCard(id, x, yy, w, h);
			else chartCard(id, x, yy, w, h);
		}

		function table(head, body, opts) {
			doc.autoTable(Object.assign({
				startY: y,
				head: [head.map(t)],
				body: body.map((r) => r.map((c) => t(c))),
				margin: { left: M, right: M, top: TOP + 2, bottom: H - BOTTOM + 2 },
				theme: 'plain',
				styles: { font: 'helvetica', fontSize: 8, cellPadding: { top: 2.4, bottom: 2.4, left: 3, right: 3 }, textColor: NAVY, fillColor: WHITE, lineColor: LINE, lineWidth: { bottom: 0.2 }, overflow: 'linebreak', valign: 'middle' },
				headStyles: { fillColor: INK, textColor: WHITE, fontStyle: 'bold', fontSize: 7.4, cellPadding: { top: 3, bottom: 3, left: 3, right: 3 } },
				alternateRowStyles: { fillColor: [249, 250, 252] },
				rowPageBreak: 'avoid',
				willDrawPage: paintBg
			}, opts || {}));
			y = doc.lastAutoTable.finalY + 10;
		}
		const badgeCells = (colIndex) => (data) => {
			if (data.section !== 'body' || data.column.index !== colIndex) return;
			const c = SEV[data.cell.raw] || LVL[data.cell.raw] || STAT[String(data.cell.raw).replace(' ', '_')];
			if (c) { data.cell.styles.textColor = c; data.cell.styles.fontStyle = 'bold'; }
		};
		const when = (ms) => (LA.dayLabel(ms) ? LA.dayLabel(ms) + ', ' : new Date(ms).toLocaleDateString('en-US', { day: 'numeric', month: 'short' }) + ', ') + new Date(ms).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });

		// =================================================================
		// PAGE 1 – COVER + EXECUTIVE SUMMARY
		// =================================================================
		paintBg();
		doc.setFillColor(...NAVY);
		doc.rect(0, 0, W, 92, 'F');
		alpha(0.2, () => { doc.setFillColor(...INDIGO); doc.circle(W - 26, 10, 48, 'F'); });
		alpha(0.16, () => { doc.setFillColor(...CYAN); doc.circle(W - 2, 70, 30, 'F'); });
		doc.setFillColor(...INDIGO); doc.rect(0, 92, W, 1.6, 'F');
		doc.setFillColor(...CYAN); doc.rect(W * 0.62, 92, W * 0.38, 1.6, 'F');

		rr(M, 14, 12, 12, 3, INDIGO);
		doc.setDrawColor(...WHITE); doc.setLineWidth(0.7); doc.setLineCap('round'); doc.setLineJoin('round');
		doc.lines([[2, 0], [1.5, -4], [2, 8], [1.5, -4], [2, 0]], M + 1.5, 20, [1, 1], 'S', false);
		doc.setLineCap('butt');
		text('LOG ANALYZER', M + 16, 19, { size: 8, bold: true, color: [165, 180, 252] });
		text(`${BRAND} · ${TAGLINE}`, M + 16, 24, { size: 8, color: [148, 163, 184] });
		text('Generated ' + fmtDT(generated), W - M, 19, { size: 8, color: [148, 163, 184], align: 'right' });

		text('Observability Report', M, 50, { size: 30, bold: true, color: WHITE });
		text('Log analytics, incident intelligence and service health', M, 59, { size: 11.5, color: [203, 213, 225] });
		const pillW = textW(rangeLabel, 9, true) + 10;
		rr(M, 66, pillW, 7.5, 3.7, [55, 48, 163]);
		text(rangeLabel, M + 5, 71, { size: 9, bold: true, color: [224, 231, 255] });
		text(`${period}  (${tz})`, M, 83, { size: 8.5, color: [148, 163, 184] });

		y = 106;
		text('Executive summary', M, y, { size: 14, bold: true });
		text(`Based on ${nf.format(tot.total)} log events from ${s.services.length} services, persisted in the H2 log store.`, M, y + 5.5, { size: 8.5, color: FAINT });

		const cardY = y + 10, tileH = 38;
		healthCard(M, cardY, 58, tileH * 2 + 4);
		kpiGrid(['kpi-total', 'kpi-error', 'kpi-warn', 'kpi-services'], M + 62, cardY, 2, (CW - 62 - 4) / 2, tileH);

		y = cardY + tileH * 2 + 13;
		text('Key findings', M, y, { size: 11.5, bold: true });
		y += 2;
		const findings = [];
		findings.push(`Overall health is ${status.toLowerCase()} at ${s.healthScore.toFixed(1)}/100, with an error rate of ${pct(s.errorRate)} and a warning rate of ${pct(s.warnRate)}.`);
		if (biz) {
			const im = biz.impact, breached = im.sla.filter((x) => x.status === 'BREACHED').map((x) => x.service);
			findings.push(`Estimated business impact: ${B.inr(im.valueAtRisk)} at risk across ${nf.format(im.failedTransactions)} failed customer transactions (~${nf.format(im.customersAffected)} customers)${breached.length ? '; out of SLA: ' + breached.join(', ') : '; every service within SLA'}.`);
			if (im.incidents) findings.push(`${im.incidents} incident${im.incidents === 1 ? '' : 's'} raised by alert rules (${im.openIncidents} still active); mean time to acknowledge ${B.dur(im.mttaMinutes)}, mean time to resolve ${B.dur(im.mttrMinutes)}.`);
		}
		const dErr = LA.delta(errors, prev ? prev.error + prev.fatal : null, true);
		if (dErr && dErr.dir !== 'flat') findings.push(`Errors are ${dErr.dir} ${Math.abs(dErr.pct).toFixed(1)}% compared with the previous ${rangeLabel.replace('Last ', '').toLowerCase()} (${nf.format(errors)} vs ${nf.format(prev.error + prev.fatal)}).`);
		if (s.categories.length) {
			const c = s.categories[0];
			findings.push(`The leading error class is ${c.name}: ${nf.format(c.count)} errors (${c.share.toFixed(1)}% of all errors), mainly from ${c.services.slice(0, 3).join(', ')}.`);
		}
		const worst = [...s.services].sort((a, b) => b.errorRate - a.errorRate)[0];
		if (worst) findings.push(`The most affected service is ${worst.name} with a ${worst.errorRate.toFixed(2)}% error rate (${nf.format(worst.errors)} errors), currently ${worst.status.toLowerCase()}.`);
		if (peakIdx >= 0 && errs[peakIdx] > 0) findings.push(`Errors peaked at ${LA.bucketRange(s.trend[peakIdx].t, s.bucketMinutes)} with ${nf.format(errs[peakIdx])} errors.`);
		findings.push(breaches ? `${breaches} of ${s.trend.length} time buckets exceeded the 3% error-rate SLO.` : 'Every time bucket stayed within the 3% error-rate SLO.');
		if (s.simulator && s.simulator.incidentService) findings.push(`An incident is in progress on ${s.simulator.incidentService}.`);

		const findingsCardY = y + 2;
		doc.setFontSize(8.8);
		// leave enough room for the contents list (one entry per section), trimming findings if needed
		const visibleNow = LA.visibleWidgets();
		const tocCount = (visibleNow.some((id) => id.startsWith('kpi-')) ? 1 : 0) + visibleNow.filter((id) => !id.startsWith('kpi-')).length;
		const tocNeed = 9 + 7 + Math.ceil(tocCount / (tocCount > 8 ? 3 : 2)) * 5.4;
		let wrapped = findings.slice(0, 5).map((f) => doc.splitTextToSize(t(f), CW - 16));
		const heightOf = (ws) => ws.reduce((a, l) => a + l.length * 4.1 + 2.6, 6);
		while (wrapped.length > 3 && findingsCardY + heightOf(wrapped) + tocNeed > H - 16) wrapped = wrapped.slice(0, -1);
		const fh = heightOf(wrapped);
		card(M, findingsCardY, CW, fh);
		y = findingsCardY + 6;
		wrapped.forEach((lines) => {
			doc.setFillColor(...INDIGO);
			doc.circle(M + 6, y + 1.9, 0.9, 'F');
			text(lines, M + 9.5, y + 3, { size: 8.8, color: [51, 65, 85] });
			y += lines.length * 4.1 + 2.6;
		});
		const tocY = findingsCardY + fh + 9;

		// =================================================================
		// CONTENT PAGES – visible widgets in dashboard order
		// =================================================================
		newPage();
		const visible = LA.visibleWidgets();
		const kpiIds = visible.filter((id) => id.startsWith('kpi-'));
		let kpisDone = false;
		const tick = (id) => { overlay(true, 'Adding ' + ((LA.widgetMeta(id) || {}).name || id) + '…'); return new Promise((r) => setTimeout(r, 0)); };

		for (let i = 0; i < visible.length; i++) {
			const id = visible[i];
			await tick(id);

			if (id.startsWith('kpi-')) {
				if (kpisDone) continue;
				kpisDone = true;
				const rowsH = Math.ceil(kpiIds.length / 4) * 42;
				ensure(rowsH + 12);
				sectionTitle('Key metrics', `${rangeLabel} · deltas compare with the previous period of the same length`);
				y = kpiGrid(kpiIds, M, y, 4, (CW - 12) / 4, 38) + 10;
				continue;
			}

			if (isHalf(id)) {
				// pair with the next visible half-width widget, if it comes right after
				const next = visible[i + 1];
				if (next && isHalf(next)) {
					ensure(HALF_H + 2);
					tocEntry(TITLES[id]); tocEntry(TITLES[next]);
					drawWidget(id, M, y, HALF, HALF_H);
					await tick(next);
					drawWidget(next, M + HALF + GAP, y, HALF, HALF_H);
					y += HALF_H + 8;
					i++;
				} else {
					const h = id === 'donut' || id === 'formats' ? 84 : id === 'health' ? 62 : FULL_CHART_H;
					ensure(h + 2);
					tocEntry(TITLES[id]);
					drawWidget(id, M, y, CW, h);
					y += h + 8;
				}
				continue;
			}

			switch (id) {
				case 'trend':
				case 'volume':
					ensure(FULL_CHART_H + 2);
					tocEntry(TITLES[id]);
					chartCard(id, M, y, CW, FULL_CHART_H);
					y += FULL_CHART_H + 8;
					break;
				case 'categories': {
					const h = 26 + Math.min(10, s.categories.length) * 7.5;
					ensure(h + 2);
					tocEntry('Top error categories');
					chartCard('categories', M, y, CW, Math.max(60, h));
					y += Math.max(60, h) + 8;
					if (s.categories.length) {
						ensure(30);
						table(['Category', 'Errors', 'Share', 'Severity', 'Last seen', 'Main services'],
							s.categories.map((c) => [c.name, nf.format(c.count), c.share.toFixed(1) + '%', c.severity, when(c.lastSeen), c.services.slice(0, 3).join(', ')]),
							{ columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right' }, 5: { cellWidth: 52 } }, didParseCell: badgeCells(3) });
					}
					break;
				}
				case 'aianalysis': {
					const a = window.LA_AI && window.LA_AI.overview;
					if (!a) break; // nothing analysed yet: leave the section out
					ensure(60);
					sectionTitle(a.source === 'GEMINI' ? 'AI error analysis' : 'Error analysis', `${a.source === 'GEMINI' ? 'Gemini (' + a.model + ')' : 'NLP engine: pattern mining + BFSI playbook'} · severity ${a.severity}${a.confidence != null ? ' · confidence ' + a.confidence + '%' : ''} · generated ${fmtDT(a.generatedAt)}`);
					const steps = (a.recoverySteps || []).map((x, n) => `${n + 1}. ${x}`).join('\n');
					const prev = (a.preventiveActions || []).map((x) => '- ' + x).join('\n');
					table(['Section', 'Analysis'], [['Summary', a.summary || '-'], ['Probable root cause', a.rootCause || '-'], ['Impact', a.impact || '-'],
						['Recovery steps', steps || '-'], ['Prevention', prev || '-']].concat((a.evidence || []).length ? [['Evidence', a.evidence.map((x) => '- ' + x).join('\n')]] : []),
						{ styles: { fontSize: 8.4, cellPadding: { top: 3, bottom: 3, left: 3.5, right: 3.5 } }, columnStyles: { 0: { cellWidth: 36, fontStyle: 'bold', textColor: INK } } });
					if ((a.incidents || []).length) {
						ensure(30);
						table(['Incident', 'Severity', 'Root cause', 'Do first'], a.incidents.map((x) => [x.title + (x.pattern ? ' (pattern #' + x.pattern + ')' : ''), x.severity, x.rootCause, x.recovery]),
							{ styles: { fontSize: 7.6 }, columnStyles: { 0: { cellWidth: 40, fontStyle: 'bold' }, 1: { cellWidth: 18 } }, didParseCell: badgeCells(1) });
					}
					break;
				}
				case 'impact': {
					if (!biz) break;
					const im = biz.impact;
					ensure(70);
					sectionTitle('Business impact', 'Estimated from failed payment events · ' + rangeLabel.toLowerCase());
					card(M, y, CW, 30);
					text(im.headline, M + 6, y + 7.5, { size: 8.6, color: SLATE, maxWidth: CW - 12 });
					const bw = (CW - 12 - 9) / 4;
					[['VALUE AT RISK', B.inr(im.valueAtRisk), BAD], ['FAILED TRANSACTIONS', nf.format(im.failedTransactions), NAVY],
						['CUSTOMERS AFFECTED', nf.format(im.customersAffected), NAVY], ['MTTA / MTTR', B.dur(im.mttaMinutes) + ' / ' + B.dur(im.mttrMinutes), NEUTRAL]]
						.forEach((b, n) => statBox(M + 6 + n * (bw + 3), y + 12.5, bw, 13.5, b[0], b[1], b[2]));
					y += 36;
					if (im.byService.length) {
						table(['Service', 'Owner', 'Value at risk', 'Failed transactions', 'Customers affected'],
							im.byService.map((x) => [x.service, x.owner, B.inr(x.valueAtRisk), nf.format(x.failedTransactions), nf.format(x.customersAffected)]),
							{ columnStyles: { 2: { halign: 'right', fontStyle: 'bold' }, 3: { halign: 'right' }, 4: { halign: 'right' } } });
					}
					doc.setFontSize(7);
					const method = doc.splitTextToSize(t('Method: ' + im.method), CW);
					ensure(method.length * 3.2 + 6);
					text(method, M, y - 4, { size: 7, color: FAINT });
					y += method.length * 3.2 + 4;
					break;
				}
				case 'sla': {
					if (!biz) break;
					const im = biz.impact;
					ensure(50);
					sectionTitle('SLA & error budget', `Availability in 5-minute slots against each service's target · ${im.slaBreached} breached, ${im.slaAtRisk} at risk, ${im.slaMet} met`);
					table(['Service', 'Owner', 'Target', 'Availability', 'Unavailable', 'Error budget used', 'Status'],
						im.sla.map((x) => [x.service, x.owner, x.target + '%', x.availability.toFixed(3) + '%', x.downMinutes + ' min', (x.budgetUsed >= 1000 ? '>999' : Math.round(x.budgetUsed)) + '%', x.status.replace('_', ' ')]),
						{ columnStyles: { 2: { halign: 'right' }, 3: { halign: 'right' }, 4: { halign: 'right' }, 5: { halign: 'right' } }, didParseCell: badgeCells(6) });
					break;
				}
				case 'incidentdesk': {
					if (!biz) break;
					const l = biz.list, im = biz.impact;
					ensure(40);
					sectionTitle('Incidents', `${l.items.length} in period · ${l.open} open, ${l.acknowledged} acknowledged · MTTA ${B.dur(im.mttaMinutes)} · MTTR ${B.dur(im.mttrMinutes)}`);
					if (!l.items.length) { text('No incidents in this period.', M, y + 2, { size: 9, color: SLATE }); y += 12; break; }
					table(['ID', 'Severity', 'Incident', 'Owner', 'Opened', 'Duration', 'Status'],
						l.items.slice(0, 30).map((x) => [B.incId(x), x.severity, x.title, x.owner, when(x.openedAt), B.dur(x.durationMinutes), x.status + (x.resolvedBy ? '\nby ' + x.resolvedBy : x.acknowledgedBy ? '\nby ' + x.acknowledgedBy : '')]),
						{ styles: { fontSize: 7.4 }, columnStyles: { 0: { cellWidth: 17, textColor: FAINT }, 1: { cellWidth: 18 }, 3: { cellWidth: 27 }, 4: { cellWidth: 26 }, 5: { cellWidth: 19 }, 6: { cellWidth: 28 } },
							didParseCell: (d) => { badgeCells(1)(d); if (d.section === 'body' && d.column.index === 6) { const c = STAT[String(d.cell.raw).split('\n')[0]]; if (c) { d.cell.styles.textColor = c; d.cell.styles.fontStyle = 'bold'; } } } });
					break;
				}
				case 'incidents':
					ensure(40);
					sectionTitle('Critical error patterns', 'Error categories ranked by severity (errors per hour) and volume');
					if (!s.categories.length) { text('No errors in this period. All systems nominal.', M, y + 2, { size: 9, color: SLATE }); y += 12; break; }
					table(['#', 'Incident category', 'Severity', 'Errors', 'Share', 'Affected services'],
						s.categories.slice(0, 8).map((c, n) => [String(n + 1), c.name, c.severity, nf.format(c.count), c.share.toFixed(1) + '%', c.services.join(', ')]),
						{ columnStyles: { 0: { cellWidth: 8, textColor: FAINT }, 3: { halign: 'right' }, 4: { halign: 'right' }, 5: { cellWidth: 70 } }, didParseCell: badgeCells(2) });
					break;
				case 'services':
					ensure(50);
					sectionTitle('Service health', `Volume and error rate per service · ${healthy} of ${s.services.length} healthy`);
					table(['Service', 'Events', 'Warnings', 'Errors', 'Error rate', 'Status', 'Last event'],
						[...s.services].sort((a, b) => b.errorRate - a.errorRate).map((x) => [x.name, nf.format(x.total), nf.format(x.warnings), nf.format(x.errors), x.errorRate.toFixed(2) + '%', x.status, when(x.lastSeen)]),
						{
							columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right' }, 3: { halign: 'right' }, 4: { halign: 'right' } },
							didParseCell: badgeCells(5),
							didDrawCell: (d) => { // inline error-rate bar
								if (d.section !== 'body' || d.column.index !== 4) return;
								const v = parseFloat(d.cell.raw) || 0, max = Math.max(...s.services.map((x) => x.errorRate), 1);
								doc.setFillColor(254, 226, 226); doc.rect(d.cell.x + 2, d.cell.y + d.cell.height - 1.6, d.cell.width - 4, 0.8, 'F');
								doc.setFillColor(244, 63, 94); doc.rect(d.cell.x + 2, d.cell.y + d.cell.height - 1.6, (d.cell.width - 4) * v / max, 0.8, 'F');
							}
						});
					break;
				case 'livefeed': {
					const items = LA.feed.items.slice(0, 25);
					ensure(40);
					sectionTitle('Latest events', `The ${items.length} most recently stored logs from the live stream at export time`);
					table(['Day', 'Date', 'Time', 'Level', 'Service', 'Message'],
						items.map((r) => {
							const ft = LA.fmtLogTime(r.timestamp);
							return [ft.rel || '-', ft.full, ft.time, r.level, r.service, r.message.length > 150 ? r.message.slice(0, 147) + '...' : r.message];
						}),
						{ styles: { fontSize: 7.2 }, columnStyles: { 0: { cellWidth: 17, fontStyle: 'bold', textColor: INDIGO }, 1: { cellWidth: 26 }, 2: { cellWidth: 19 }, 3: { cellWidth: 14 }, 4: { cellWidth: 27 } }, didParseCell: badgeCells(3) });
					break;
				}
				case 'explorer': {
					const e = LA.explorer;
					let page;
					try { page = await LA.api('/api/logs?' + LA.explorerParams({ page: 0, size: 50 })); } catch (err) { break; }
					const filters = [`range ${s.range}`];
					if (e.q) filters.push(`search "${e.q}"`);
					if (e.levels.size) filters.push('levels ' + [...e.levels].join(', '));
					if (e.service) filters.push('service ' + e.service);
					if (e.category) filters.push('category ' + e.category);
					filters.push(`sorted by ${e.sort} ${e.dir}`);
					ensure(40);
					sectionTitle('Log extract', `Explorer view: ${filters.join(' · ')} · ${Math.min(50, page.totalElements)} of ${nf.format(page.totalElements)} matching logs`);
					table(['When', 'Level', 'Service', 'Category', 'Message'],
						page.content.map((r) => {
							const ft = LA.fmtLogTime(r.timestamp);
							return [(ft.rel ? ft.rel + ' ' : '') + ft.time + '\n' + ft.full, r.level, r.service, r.category || '-', r.message.length > 160 ? r.message.slice(0, 157) + '...' : r.message];
						}),
						{ styles: { fontSize: 7.2 }, columnStyles: { 0: { cellWidth: 30 }, 1: { cellWidth: 14 }, 2: { cellWidth: 27 }, 3: { cellWidth: 28 } }, didParseCell: badgeCells(1) });
					break;
				}
				default:
					break;
			}
		}

		// =================================================================
		// CONTENTS on the cover, then headers / footers on every page
		// =================================================================
		const pages = doc.getNumberOfPages();
		doc.setPage(1);
		text('Contents', M, tocY, { size: 11.5, bold: true });
		const room = H - 16 - (tocY + 7);
		const cols = Math.ceil(toc.length / 2) * 5.4 > room ? 3 : 2;
		const perCol = Math.ceil(toc.length / cols), colW = (CW - 8 * (cols - 1)) / cols;
		const rowH = Math.min(5.4, room / Math.max(1, perCol - 1 + 0.6));
		toc.forEach((entry, n) => {
			const x = M + Math.floor(n / perCol) * (colW + 8), yy = tocY + 7 + (n % perCol) * rowH;
			const label = `${n + 1}.  ${entry.title}`, size = cols === 3 ? 8.3 : 9;
			text(label, x, yy, { size, color: [51, 65, 85] });
			doc.setDrawColor(203, 213, 225); doc.setLineWidth(0.2); doc.setLineDashPattern([0.4, 1], 0);
			doc.line(x + textW(label, size) + 2, yy - 0.8, x + colW - 6, yy - 0.8);
			doc.setLineDashPattern([], 0);
			text(String(entry.page), x + colW, yy, { size, bold: true, align: 'right', color: INDIGO });
		});

		for (let p = 1; p <= pages; p++) {
			doc.setPage(p);
			if (p > 1) {
				doc.setFillColor(...WHITE); doc.rect(0, 0, W, 15.5, 'F');
				doc.setFillColor(...INDIGO); doc.rect(0, 0, W, 2, 'F');
				doc.setFillColor(...CYAN); doc.rect(W * 0.62, 0, W * 0.38, 2, 'F');
				rr(M, 5.6, 5.5, 5.5, 1.4, INDIGO);
				doc.setDrawColor(...WHITE); doc.setLineWidth(0.35); doc.setLineCap('round');
				doc.lines([[0.9, 0], [0.7, -1.8], [0.9, 3.6], [0.7, -1.8], [0.9, 0]], M + 0.7, 8.35, [1, 1], 'S', false);
				doc.setLineCap('butt');
				text(`${BRAND} · Observability Report`, M + 8, 9.6, { size: 8.5, bold: true });
				text(`${rangeLabel} · ${period}`, W - M, 9.6, { size: 7.4, color: FAINT, align: 'right' });
				doc.setDrawColor(...LINE); doc.setLineWidth(0.3); doc.line(0, 15.5, W, 15.5);
			}
			doc.setDrawColor(...LINE); doc.setLineWidth(0.3); doc.line(M, H - 11.5, W - M, H - 11.5);
			text(`Confidential - for internal use only · Log Analyzer · ${BRAND}`, M, H - 6.5, { size: 7, color: FAINT });
			text(`Page ${p} of ${pages}`, W - M, H - 6.5, { size: 7.5, bold: true, color: SLATE, align: 'right' });
		}

		const stamp = new Date(generated);
		const pad = (n) => String(n).padStart(2, '0');
		const safeBrand = BRAND.replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'Report';
		const name = `${safeBrand}-Observability-Report_${s.range}_${stamp.getFullYear()}-${pad(stamp.getMonth() + 1)}-${pad(stamp.getDate())}_${pad(stamp.getHours())}${pad(stamp.getMinutes())}.pdf`;
		doc.save(name);
		return { name, pages };
	}

	async function exportPdf() {
		const btn = document.getElementById('pdfBtn');
		btn.classList.add('busy');
		overlay(true, 'Preparing report…');
		try {
			await ensureLibs();
			const r = await buildReport();
			window.LA.toast(`Downloaded ${r.pages}-page report: ${r.name}`);
		} catch (e) {
			console.error(e);
			window.LA.toast('PDF export failed: ' + e.message);
		} finally {
			overlay(false);
			btn.classList.remove('busy');
		}
	}

	document.getElementById('pdfBtn').addEventListener('click', exportPdf);
	window.LA_exportPdf = exportPdf;
})();
