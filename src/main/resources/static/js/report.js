/* AI Log Analyzer – professional multi-page PDF report of the current dashboard (jsPDF + AutoTable). */
(function () {
	'use strict';

	const LIBS = [
		'https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js',
		'https://cdnjs.cloudflare.com/ajax/libs/jspdf-autotable/3.8.2/jspdf.plugin.autotable.min.js'
	];

	// ---------- palette (RGB) ----------
	const NAVY = [15, 23, 42], SLATE = [71, 85, 105], FAINT = [124, 135, 154], LINE = [226, 232, 240], SOFT = [248, 250, 252];
	const INDIGO = [79, 70, 229], CYAN = [6, 182, 212];
	const SEV = { CRITICAL: [225, 29, 72], HIGH: [239, 68, 68], MEDIUM: [217, 119, 6], DEGRADED: [217, 119, 6], LOW: [37, 99, 235], HEALTHY: [5, 150, 105] };
	const LVL = { FATAL: [159, 18, 57], ERROR: [239, 68, 68], WARN: [217, 119, 6], INFO: [16, 185, 129], DEBUG: [100, 116, 139], TRACE: [100, 116, 139] };

	// ---------- page geometry (mm) ----------
	const W = 210, H = 297, M = 14, CW = W - 2 * M, TOP = 22, BOTTOM = H - 18;

	const nf = new Intl.NumberFormat('en-US');
	const pct = (v) => (v < 10 ? v.toFixed(2) : v.toFixed(1)) + '%';
	const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'local time';
	const fmtDT = (ms) => new Date(ms).toLocaleString('en-US', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });

	/** Standard PDF fonts only cover Latin-1, so map or drop anything else. */
	function t(s) {
		if (s == null) return '';
		return String(s)
			.replace(/[–—]/g, '-').replace(/…/g, '...').replace(/[‘’]/g, "'").replace(/[“”]/g, '"')
			.replace(/→/g, '->').replace(/⏎/g, ' ').replace(/[^\x09\x0A\x0D\x20-\xFF]/g, '');
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
		if (!(window.jspdf && window.jspdf.jsPDF)) await loadScript(LIBS[0]);
		if (!window.jspdf.jsPDF.API.autoTable) await loadScript(LIBS[1]);
	}

	function overlay(show, text) {
		let o = document.getElementById('pdfOverlay');
		if (!o) {
			o = document.createElement('div');
			o.id = 'pdfOverlay';
			o.className = 'overlay';
			o.innerHTML = '<div class="overlay-box"><div class="spinner"></div><div class="card-title" id="pdfOverlayText">Building report…</div><div class="card-sub" style="margin-top:6px">Rendering charts in high resolution</div></div>';
			document.body.appendChild(o);
		}
		if (text) document.getElementById('pdfOverlayText').textContent = text;
		o.classList.toggle('show', show);
	}

	/** Captures a Chart.js chart at 3x pixel density for crisp print output. */
	function chartImage(chart) {
		const original = chart.options.devicePixelRatio;
		chart.options.devicePixelRatio = 3;
		chart.resize();
		chart.update('none');
		const data = chart.toBase64Image('image/png', 1);
		const ratio = chart.canvas.width / chart.canvas.height;
		chart.options.devicePixelRatio = original;
		chart.resize();
		chart.update('none');
		return { data, ratio };
	}

	// =====================================================================
	// REPORT BUILDER
	// =====================================================================
	async function buildReport() {
		const LA = window.LA, s = LA.summary;
		if (!s) throw new Error('Dashboard data not loaded yet');
		const { jsPDF } = window.jspdf;
		const doc = new jsPDF({ unit: 'mm', format: 'a4', compress: true });
		doc.setProperties({ title: 'NovaBank Observability Report', subject: 'Log analytics and incident report', author: 'AI Log Analyzer', creator: 'AI Log Analyzer' });

		const generated = Date.now();
		const rangeLabel = LA.RANGE_LABEL[s.range] || s.range;
		const period = `${fmtDT(s.from)}  to  ${fmtDT(s.to)}`;
		const tot = s.totals, errors = tot.error + tot.fatal;
		const healthy = s.services.filter((x) => x.status === 'HEALTHY').length;
		const status = s.healthScore >= 85 ? 'HEALTHY' : s.healthScore >= 60 ? 'DEGRADED' : 'CRITICAL';
		const statusColor = SEV[status];

		let y = TOP;
		const toc = [];
		const newPage = () => { doc.addPage(); y = TOP + 4; };
		const ensure = (h) => { if (y + h > BOTTOM) newPage(); };

		const text = (str, x, yy, o) => {
			o = o || {};
			doc.setFont('helvetica', o.bold ? 'bold' : 'normal');
			doc.setFontSize(o.size || 10);
			doc.setTextColor(...(o.color || NAVY));
			doc.text(Array.isArray(str) ? str.map(t) : t(str), x, yy, { align: o.align || 'left', maxWidth: o.maxWidth, baseline: o.baseline || 'alphabetic' });
		};
		const roundRect = (x, yy, w, h, r, fill, stroke) => {
			if (fill) doc.setFillColor(...fill);
			if (stroke) { doc.setDrawColor(...stroke); doc.setLineWidth(0.25); }
			doc.roundedRect(x, yy, w, h, r, r, fill && stroke ? 'FD' : fill ? 'F' : 'S');
		};
		const tint = (rgb, a) => rgb.map((c) => Math.round(255 - (255 - c) * a));

		function section(title, sub, minHeight) {
			ensure((minHeight || 40) + 14);
			toc.push({ title, page: doc.getNumberOfPages() });
			doc.setFillColor(...INDIGO);
			doc.roundedRect(M, y - 4.2, 1.4, 6.2, 0.6, 0.6, 'F');
			text(title, M + 4, y, { bold: true, size: 13 });
			if (sub) text(sub, M + 4, y + 5, { size: 8.5, color: FAINT });
			y += sub ? 10 : 7;
		}

		function table(head, body, opts) {
			doc.autoTable(Object.assign({
				startY: y,
				head: [head.map(t)],
				body: body.map((r) => r.map((c) => t(c))),
				margin: { left: M, right: M, top: TOP + 4, bottom: H - BOTTOM + 2 },
				theme: 'plain',
				styles: { font: 'helvetica', fontSize: 8, cellPadding: { top: 2.2, bottom: 2.2, left: 2.5, right: 2.5 }, textColor: NAVY, lineColor: LINE, lineWidth: { bottom: 0.2 }, overflow: 'linebreak', valign: 'middle' },
				headStyles: { fillColor: NAVY, textColor: [255, 255, 255], fontStyle: 'bold', fontSize: 7.5, cellPadding: { top: 2.6, bottom: 2.6, left: 2.5, right: 2.5 } },
				alternateRowStyles: { fillColor: SOFT },
				rowPageBreak: 'avoid'
			}, opts || {}));
			y = doc.lastAutoTable.finalY + 9;
		}

		/** Colours a cell holding a severity/level/status keyword. */
		const badgeCells = (colIndex) => (data) => {
			if (data.section !== 'body' || data.column.index !== colIndex) return;
			const c = SEV[data.cell.raw] || LVL[data.cell.raw];
			if (c) { data.cell.styles.textColor = c; data.cell.styles.fontStyle = 'bold'; }
		};

		function chartSection(key, title, sub, maxH) {
			const chart = LA.charts[key];
			const img = chartImage(chart);
			let w = CW, h = w / img.ratio;
			if (h > (maxH || 88)) { h = maxH || 88; w = h * img.ratio; }
			section(title, sub, h + 4);
			roundRect(M, y - 2, CW, h + 6, 2.5, [255, 255, 255], LINE);
			doc.addImage(img.data, 'PNG', M + (CW - w) / 2 + 1, y + 1, w - 2, h - 0.5, undefined, 'FAST');
			y += h + 10;
		}

		function donutSection(key, title, sub, rows) {
			const img = chartImage(LA.charts[key]);
			const size = 62;
			section(title, sub, size + 6);
			roundRect(M, y - 2, CW, size + 8, 2.5, [255, 255, 255], LINE);
			const iw = Math.min(size, size * img.ratio), ih = iw / img.ratio;
			doc.addImage(img.data, 'PNG', M + 6, y + 1 + (size - ih) / 2, iw, ih, undefined, 'FAST');
			const startY = y;
			doc.autoTable({
				startY: y + 3, margin: { left: M + size + 16, right: M + 6 }, theme: 'plain',
				head: [rows.head.map(t)], body: rows.body.map((r) => r.map(t)),
				styles: { fontSize: 8.5, cellPadding: 1.8, textColor: NAVY }, headStyles: { textColor: FAINT, fontStyle: 'bold', fontSize: 7.5 },
				columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right', textColor: FAINT } },
				didDrawCell: (d) => {
					if (d.section === 'body' && d.column.index === 0 && rows.colors[d.row.index]) {
						doc.setFillColor(...rows.colors[d.row.index]);
						doc.roundedRect(d.cell.x - 3.2, d.cell.y + d.cell.height / 2 - 1.2, 2.4, 2.4, 0.5, 0.5, 'F');
					}
				}
			});
			y = Math.max(startY + size + 14, doc.lastAutoTable.finalY + 8);
		}

		// ---------------- KPI tiles ----------------
		const minutes = Math.max(1, (s.to - s.from) / 60000);
		const peakPerMin = Math.max(0, ...s.trend.map((p) => p.total)) / s.bucketMinutes;
		const openIncidents = s.categories.filter((c) => c.severity === 'CRITICAL' || c.severity === 'HIGH');
		const KPI = {
			'kpi-total': ['Total logs', nf.format(tot.total), (tot.total / minutes).toFixed(1) + ' events/min', [99, 102, 241]],
			'kpi-info': ['Info', nf.format(tot.info), pct(tot.total ? tot.info * 100 / tot.total : 0) + ' of volume', [16, 185, 129]],
			'kpi-warn': ['Warnings', nf.format(tot.warn), pct(s.warnRate) + ' of volume', [217, 119, 6]],
			'kpi-error': ['Errors', nf.format(errors), pct(s.errorRate) + (tot.fatal ? ` · ${tot.fatal} fatal` : ' of volume'), [239, 68, 68]],
			'kpi-throughput': ['Throughput', (tot.total / minutes).toFixed(1) + '/min', 'peak ' + peakPerMin.toFixed(1) + '/min', [14, 165, 233]],
			'kpi-fatal': ['Fatal events', nf.format(tot.fatal), 'system-critical failures', [159, 18, 57]],
			'kpi-services': ['Healthy services', `${healthy}/${s.services.length}`, s.services.length ? Math.round(healthy * 100 / s.services.length) + '% healthy' : '-', [5, 150, 105]],
			'kpi-incidents': ['Open incidents', String(openIncidents.length), openIncidents.length ? 'top: ' + openIncidents[0].name : 'none', [225, 29, 72]]
		};

		function kpiTiles(ids, startY, cols, tileW, tileH, x0) {
			ids.forEach((id, i) => {
				const [label, value, foot, c] = KPI[id];
				const x = x0 + (i % cols) * (tileW + 4), yy = startY + Math.floor(i / cols) * (tileH + 4);
				roundRect(x, yy, tileW, tileH, 2.5, [255, 255, 255], LINE);
				doc.setFillColor(...c);
				doc.roundedRect(x, yy, 1.4, tileH, 0.7, 0.7, 'F');
				text(label.toUpperCase(), x + 5, yy + 6.5, { size: 6.8, bold: true, color: SLATE });
				text(value, x + 5, yy + 15.5, { size: 15, bold: true });
				text(foot, x + 5, yy + tileH - 4, { size: 7, color: c, maxWidth: tileW - 8 });
			});
			return startY + Math.ceil(ids.length / cols) * (tileH + 4);
		}

		// =================================================================
		// PAGE 1 – COVER + EXECUTIVE SUMMARY
		// =================================================================
		doc.setFillColor(...NAVY);
		doc.rect(0, 0, W, 96, 'F');
		// decorative glows
		doc.setGState(new doc.GState({ opacity: 0.18 }));
		doc.setFillColor(...INDIGO); doc.circle(W - 28, 14, 46, 'F');
		doc.setFillColor(...CYAN); doc.circle(W - 4, 70, 30, 'F');
		doc.setGState(new doc.GState({ opacity: 1 }));
		doc.setFillColor(...INDIGO); doc.rect(0, 96, W, 1.6, 'F');
		doc.setFillColor(...CYAN); doc.rect(W * 0.62, 96, W * 0.38, 1.6, 'F');

		// logo mark
		roundRect(M, 14, 12, 12, 3, INDIGO);
		doc.setDrawColor(255, 255, 255); doc.setLineWidth(0.7); doc.setLineCap('round'); doc.setLineJoin('round');
		doc.lines([[2, 0], [1.5, -4], [2, 8], [1.5, -4], [2, 0]], M + 1.5, 20, [1, 1], 'S', false);
		text('AI LOG ANALYZER', M + 16, 19, { size: 8, bold: true, color: [165, 180, 252] });
		text('NovaBank · BFSI Observability', M + 16, 24, { size: 8, color: [148, 163, 184] });
		text('Generated ' + fmtDT(generated), W - M, 19, { size: 8, color: [148, 163, 184], align: 'right' });

		text('Observability Report', M, 52, { size: 30, bold: true, color: [255, 255, 255] });
		text('Log analytics, incident intelligence and service health', M, 61, { size: 11.5, color: [203, 213, 225] });
		doc.setFont('helvetica', 'bold'); doc.setFontSize(9);
		const pillW = doc.getTextWidth(t(rangeLabel)) + 10;
		roundRect(M, 68, pillW, 7.5, 3.7, [49, 46, 129]);
		text(rangeLabel, M + 5, 73, { size: 9, bold: true, color: [224, 231, 255] });
		text(`${period}  (${tz})`, M, 86, { size: 8.5, color: [148, 163, 184] });

		// executive summary
		y = 110;
		text('Executive summary', M, y, { size: 14, bold: true });
		text(`Based on ${nf.format(tot.total)} log events from ${s.services.length} services, persisted in the H2 log store.`, M, y + 5.5, { size: 8.5, color: FAINT });

		const cardY = y + 10;
		roundRect(M, cardY, 58, 62, 3, tint(statusColor, 0.07), tint(statusColor, 0.35));
		text('HEALTH INDEX', M + 29, cardY + 9, { size: 7, bold: true, color: SLATE, align: 'center' });
		text(s.healthScore.toFixed(1), M + 29, cardY + 27, { size: 30, bold: true, color: statusColor, align: 'center' });
		text('out of 100', M + 29, cardY + 33, { size: 7.5, color: FAINT, align: 'center' });
		roundRect(M + 8, cardY + 38, 42, 3, 1.5, [226, 232, 240]);
		roundRect(M + 8, cardY + 38, Math.max(3, 42 * s.healthScore / 100), 3, 1.5, statusColor);
		doc.setFont('helvetica', 'bold'); doc.setFontSize(8);
		const sw = doc.getTextWidth(status) + 8;
		roundRect(M + 29 - sw / 2, cardY + 46, sw, 6.5, 3.2, tint(statusColor, 0.15));
		text(status, M + 29, cardY + 50.5, { size: 8, bold: true, color: statusColor, align: 'center' });

		const coverKpis = ['kpi-total', 'kpi-error', 'kpi-warn', 'kpi-services'];
		kpiTiles(coverKpis, cardY, 2, (CW - 62 - 4) / 2, 29, M + 62);

		// key findings
		y = cardY + 72;
		text('Key findings', M, y, { size: 11.5, bold: true });
		y += 3;
		const findings = [];
		findings.push(`Overall health is ${status.toLowerCase()} at ${s.healthScore.toFixed(1)}/100, with an error rate of ${pct(s.errorRate)} and a warning rate of ${pct(s.warnRate)}.`);
		if (s.categories.length) {
			const c = s.categories[0];
			findings.push(`The leading error class is ${c.name}: ${nf.format(c.count)} errors (${c.share.toFixed(1)}% of all errors), mainly from ${c.services.slice(0, 3).join(', ')}.`);
		}
		const worst = [...s.services].sort((a, b) => b.errorRate - a.errorRate)[0];
		if (worst) findings.push(`The most affected service is ${worst.name} with a ${worst.errorRate.toFixed(2)}% error rate (${nf.format(worst.errors)} errors), currently ${worst.status.toLowerCase()}.`);
		const errs = s.trend.map((p) => p.errors), peak = errs.indexOf(Math.max(...errs));
		if (peak >= 0 && errs[peak] > 0) findings.push(`Errors peaked at ${LA.bucketRange(s.trend[peak].t, s.bucketMinutes)} with ${nf.format(errs[peak])} errors.`);
		const breaches = s.trend.filter((p) => p.total && p.errors * 100 / p.total > 3).length;
		findings.push(breaches ? `${breaches} of ${s.trend.length} time buckets exceeded the 3% error-rate SLO.` : 'Every time bucket stayed within the 3% error-rate SLO.');
		findings.push(openIncidents.length ? `${openIncidents.length} incident categor${openIncidents.length > 1 ? 'ies are' : 'y is'} at high or critical severity and need attention.` : 'No error category is at high or critical severity.');
		if (s.simulator && s.simulator.incidentService) findings.push(`An incident is in progress on ${s.simulator.incidentService}.`);

		doc.setFontSize(9);
		findings.forEach((f) => {
			const lines = doc.splitTextToSize(t(f), CW - 8);
			y += 2;
			doc.setFillColor(...INDIGO);
			doc.circle(M + 1.4, y + 2.6, 0.9, 'F');
			text(lines, M + 5, y + 3.6, { size: 9, color: [51, 65, 85] });
			y += lines.length * 4.4 + 1;
		});

		// contents placeholder (page numbers filled in at the end)
		const tocY = y + 8;

		// =================================================================
		// CONTENT PAGES – one section per visible widget, in dashboard order
		// =================================================================
		newPage();
		const visible = LA.visibleWidgets();
		const kpiIds = visible.filter((id) => id.startsWith('kpi-'));
		let kpisDone = false;
		const bucketText = s.bucketMinutes >= 1440 ? 'day' : s.bucketMinutes >= 60 ? (s.bucketMinutes === 60 ? 'hour' : s.bucketMinutes / 60 + ' hours') : s.bucketMinutes + ' minutes';

		for (const id of visible) {
			overlay(true, 'Adding ' + (LA.widgetMeta(id) || {}).name + '…');
			await new Promise((r) => setTimeout(r, 0)); // let the overlay paint

			if (id.startsWith('kpi-')) {
				if (kpisDone) continue;
				kpisDone = true;
				section('Key metrics', `${rangeLabel} · ${period}`, Math.ceil(kpiIds.length / 4) * 30);
				y = kpiTiles(kpiIds, y, 4, (CW - 12) / 4, 26, M) + 6;
				continue;
			}
			switch (id) {
				case 'health': {
					section('System health', 'Health = 100 - (error % x 5 + warning %), clamped to 0-100', 34);
					roundRect(M, y - 2, CW, 30, 2.5, [255, 255, 255], LINE);
					text(s.healthScore.toFixed(1), M + 8, y + 13, { size: 24, bold: true, color: statusColor });
					text('/ 100 · ' + status, M + 8, y + 20, { size: 8.5, bold: true, color: statusColor });
					const bx = M + 52, bw = CW - 60;
					roundRect(bx, y + 4, bw, 4, 2, [226, 232, 240]);
					roundRect(bx, y + 4, Math.max(4, bw * s.healthScore / 100), 4, 2, statusColor);
					[[60, 'Degraded threshold'], [85, 'Healthy threshold']].forEach(([v, l]) => {
						doc.setDrawColor(...FAINT); doc.setLineWidth(0.2);
						doc.line(bx + bw * v / 100, y + 2.5, bx + bw * v / 100, y + 9.5);
						text(l + ' (' + v + ')', bx + bw * v / 100, y + 13, { size: 6.5, color: FAINT, align: 'center' });
					});
					text(`Error rate ${pct(s.errorRate)}   ·   Warning rate ${pct(s.warnRate)}   ·   ${nf.format(errors)} errors, ${nf.format(tot.warn)} warnings`, bx, y + 21, { size: 8.5, color: SLATE });
					y += 36;
					break;
				}
				case 'trend':
					chartSection('trend', 'Error trend timeline', `Errors (solid) and warnings (dashed) per ${bucketText}, ${tz}`, 80);
					break;
				case 'volume':
					chartSection('volume', 'Event volume', `Events per ${bucketText} by severity: info/debug (green), warn (amber), error (red)`, 80);
					break;
				case 'errorrate':
					chartSection('errorrate', 'Error rate over time', `Errors as % of events per ${bucketText}; dashed line is the 3% SLO`, 75);
					break;
				case 'svcerrors':
					chartSection('svcerrors', 'Errors by service', 'Error and warning counts per service', 85);
					break;
				case 'donut': {
					const L = ['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG'];
					const v = [tot.fatal, tot.error, tot.warn, tot.info, tot.debug + tot.trace];
					donutSection('donut', 'Log level distribution', 'Share of events by severity', {
						head: ['Level', 'Events', 'Share'],
						body: L.map((l, i) => [l, nf.format(v[i]), pct(tot.total ? v[i] * 100 / tot.total : 0)]),
						colors: L.map((l) => LVL[l])
					});
					break;
				}
				case 'formats': {
					const cols = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#14b8a6', '#f97316', '#ec4899', '#84cc16', '#64748b', '#a855f7']
						.map((h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)));
					const f = s.formats || [];
					donutSection('formats', 'Log format mix', 'Formats auto-detected by the universal parser', {
						head: ['Format', 'Events', 'Share'],
						body: f.map((x) => [x.label, nf.format(x.count), pct(tot.total ? x.count * 100 / tot.total : 0)]),
						colors: f.map((x, i) => cols[i % cols.length])
					});
					break;
				}
				case 'categories':
					chartSection('categories', 'Top error categories', 'BFSI incident classes by error count', 70);
					if (s.categories.length) {
						table(['Category', 'Errors', 'Share', 'Severity', 'Last seen', 'Main services'],
							s.categories.map((c) => [c.name, nf.format(c.count), c.share.toFixed(1) + '%', c.severity, LA.dayLabel(c.lastSeen) ? LA.dayLabel(c.lastSeen) + ', ' + new Date(c.lastSeen).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }) : fmtDT(c.lastSeen), c.services.slice(0, 3).join(', ')]),
							{ columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right' }, 5: { cellWidth: 52 } }, didParseCell: badgeCells(3) });
					}
					break;
				case 'incidents': {
					section('Top critical incidents', 'Error categories ranked by severity (errors per hour) and volume', 30);
					if (!s.categories.length) { text('No errors in this period. All systems nominal.', M, y + 2, { size: 9, color: SLATE }); y += 10; break; }
					table(['#', 'Incident category', 'Severity', 'Errors', 'Share', 'Affected services'],
						s.categories.slice(0, 8).map((c, i) => [String(i + 1), c.name, c.severity, nf.format(c.count), c.share.toFixed(1) + '%', c.services.join(', ')]),
						{ columnStyles: { 0: { cellWidth: 8, textColor: FAINT }, 3: { halign: 'right' }, 4: { halign: 'right' }, 5: { cellWidth: 70 } }, didParseCell: badgeCells(2) });
					break;
				}
				case 'services':
					section('Service health', `Volume and error rate per service · ${healthy} of ${s.services.length} healthy`, 40);
					table(['Service', 'Events', 'Warnings', 'Errors', 'Error rate', 'Status', 'Last event'],
						[...s.services].sort((a, b) => b.errorRate - a.errorRate).map((x) => [x.name, nf.format(x.total), nf.format(x.warnings), nf.format(x.errors), x.errorRate.toFixed(2) + '%', x.status, (LA.dayLabel(x.lastSeen) || '') + ' ' + new Date(x.lastSeen).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' })]),
						{ columnStyles: { 1: { halign: 'right' }, 2: { halign: 'right' }, 3: { halign: 'right' }, 4: { halign: 'right' } }, didParseCell: badgeCells(5) });
					break;
				case 'livefeed': {
					const items = LA.feed.items.slice(0, 25);
					section('Latest events', `The ${items.length} most recent logs from the live stream at the time of export`, 30);
					table(['Day', 'Date', 'Time', 'Level', 'Service', 'Message'],
						items.map((r) => {
							const ft = LA.fmtLogTime(r.timestamp);
							return [ft.rel || '-', ft.full, ft.time, r.level, r.service, r.message.length > 160 ? r.message.slice(0, 157) + '...' : r.message];
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
					section('Log extract', `Explorer view: ${filters.join(' · ')} · ${Math.min(50, page.totalElements)} of ${nf.format(page.totalElements)} matching logs`, 30);
					table(['When', 'Level', 'Service', 'Category', 'Message'],
						page.content.map((r) => {
							const ft = LA.fmtLogTime(r.timestamp);
							return [(ft.rel ? ft.rel + ' ' : '') + ft.time + '\n' + ft.full, r.level, r.service, r.category || '-', r.message.length > 170 ? r.message.slice(0, 167) + '...' : r.message];
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
		// 2 columns normally, 3 when the list would run into the footer
		const cols = tocY + 7 + Math.ceil(toc.length / 2) * 5.6 > H - 16 ? 3 : 2;
		const perCol = Math.ceil(toc.length / cols), colW = (CW - 8 * (cols - 1)) / cols;
		toc.forEach((entry, i) => {
			const x = M + Math.floor(i / perCol) * (colW + 8), yy = tocY + 7 + (i % perCol) * 5.6;
			text(`${i + 1}.  ${entry.title}`, x, yy, { size: cols === 3 ? 8.5 : 9, color: [51, 65, 85] });
			doc.setDrawColor(...LINE); doc.setLineWidth(0.2); doc.setLineDashPattern([0.4, 1], 0);
			doc.setFont('helvetica', 'normal'); doc.setFontSize(9);
			doc.line(x + doc.getTextWidth(t(`${i + 1}.  ${entry.title}`)) + 2, yy - 0.8, x + colW - 8, yy - 0.8);
			doc.setLineDashPattern([], 0);
			text(String(entry.page), x + colW, yy, { size: 9, bold: true, align: 'right', color: INDIGO });
		});

		for (let p = 1; p <= pages; p++) {
			doc.setPage(p);
			if (p > 1) {
				doc.setFillColor(...INDIGO); doc.rect(0, 0, W, 2.2, 'F');
				doc.setFillColor(...CYAN); doc.rect(W * 0.62, 0, W * 0.38, 2.2, 'F');
				text('NovaBank · Observability Report', M, 10.5, { size: 8.5, bold: true });
				text(`${rangeLabel} · ${period}`, W - M, 10.5, { size: 7.5, color: FAINT, align: 'right' });
				doc.setDrawColor(...LINE); doc.setLineWidth(0.3); doc.line(M, 14, W - M, 14);
			}
			doc.setDrawColor(...LINE); doc.setLineWidth(0.3); doc.line(M, H - 12, W - M, H - 12);
			text('Confidential - for internal use only · AI Log Analyzer · data from H2 log store', M, H - 7, { size: 7, color: FAINT });
			text(`Page ${p} of ${pages}`, W - M, H - 7, { size: 7.5, bold: true, color: SLATE, align: 'right' });
		}

		const stamp = new Date(generated);
		const pad = (n) => String(n).padStart(2, '0');
		const name = `NovaBank-Observability-Report_${s.range}_${stamp.getFullYear()}-${pad(stamp.getMonth() + 1)}-${pad(stamp.getDate())}_${pad(stamp.getHours())}${pad(stamp.getMinutes())}.pdf`;
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
