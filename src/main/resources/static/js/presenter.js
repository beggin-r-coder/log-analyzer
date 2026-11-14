/* Log Analyzer – presentation mode.
 * A full-screen, scripted 2-minute story with realistic mock figures for a large bank: a live service map,
 * a UPI outage, automatic detection, business impact, NLP diagnosis with change correlation, and recovery.
 * It never touches the real data or incidents. Keys: ← → Space N F Esc. */
(function () {
	'use strict';
	const LA = window.LA;
	if (!LA) return;

	const BRAND = (window.APP_BRAND && window.APP_BRAND.name) || 'NovaBank';
	const FOCUS = 'upi-switch';
	const nfmt = new Intl.NumberFormat('en-US');
	const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
	const $ = (id) => document.getElementById(id);
	const esc = LA.esc;
	const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
	const nfIn = new Intl.NumberFormat('en-IN');
	/** Indian-style money: ₹37.8 L, ₹2.66 Cr. */
	const inr = (v) => (v >= 1e7 ? '₹' + (v / 1e7).toFixed(2) + ' Cr' : v >= 1e5 ? '₹' + (v / 1e5).toFixed(1) + ' L' : '₹' + nfIn.format(Math.round(v)));
	const short = (v) => (v >= 1e6 ? (v / 1e6).toFixed(1) + 'M' : v >= 1e3 ? (v / 1e3).toFixed(1) + 'K' : String(Math.round(v)));

	// ---------- mock figures (a large retail bank at peak hour) ----------
	const M = {
		perDay: 42_700_000, perDayShort: '42.7 million', perMin: 31_240, errorRate: .42, health: 96.8,
		errMin: 2_054, failRate: 19.2, ttd: 38, rule: 'UPI payment failures', ruleErrors: 9_842, ruleEvents: 51_260, incId: 'INC-2481',
		oncall: 'A. Menon', value: 2_66_06_700, perMinLoss: 37_99_000, failedTxns: 14_382, customers: 11_906,
		budgetBefore: 23, budgetAfter: 71, patternCount: 14_382, oldMttr: 47, mttrGain: 84, annualSaving: 18_40_00_000,
		rootCause: 'Round-trip time on the primary NPCI link (MPLS-2) jumped from 38 ms to 4.1 s at onset. Requests exhaust the 30 s switch timeout and are marked deemed-failed (RespCode BT). The spike starts 3 minutes after CHG-10293 changed firewall rules on the NPCI DMZ.',
		steps: ['Fail UPI traffic over to the secondary NPCI link (MPLS-1).', 'Roll back CHG-10293 on the NPCI DMZ firewall.',
			'Run ReqChkTxn status checks for the 14,382 deemed transactions and reconcile.', 'Notify NPCI and update the customer status page.']
	};
	/** Story clock, fixed when the presentation starts so every timestamp agrees. */
	const T = {};
	function setStory() {
		const base = Date.now() + 40000; // the outage "starts" about when its slide appears
		Object.assign(T, { change: base - 168000, onset: base, detected: base + M.ttd * 1000, acked: base + 110000, diagnosed: base + 202000, resolved: base + 453000 });
	}
	const MOCK_LINES = [
		['core-banking', '', 'INFO 4221 --- [nio-8443-exec-7] c.n.cbs.ledger.PostingService : Fund transfer TXN{n12} posted: debit acct XXXXXX{n4} amount INR {amt}'],
		['upi-switch', '', '{"level":"INFO","service":"upi-switch","message":"UPI collect request NOVA{n14} from {vpa} amount INR {amt} SUCCESS"}'],
		['card-auth', '', 'ts={iso} level=info msg="Card authorisation approved" card=4XXXXXXXXXXX{n4} mcc=5411 amount={amt}'],
		['payment-gateway', '', '[INFO] [payment-gateway] [req-{n6}] Payment authorised for merchant MID{n6} order ORD{n10} via UPI'],
		['netbanking-portal', '', '49.{n2}.{n2}.{n2} - - "POST /api/v1/fund-transfer HTTP/1.1" 200 1843 rt=0.214'],
		['atm-switch', 'wn', '<134>atm-sw-02 atmd: ATM{n5} cash cassette 3 low: 12% remaining'],
		['kyc-service', '', 'INFO kyc.video - Video KYC session VK{n7} completed for CIF{n9}'],
		['fraud-engine', 'wn', '<12>1 {iso} fraud-node-1 risk - - Velocity rule R-17 flagged acct XXXXXX{n4} score=0.71'],
		['mobile-banking-api', '', '{"level":"INFO","message":"Login successful for CIF{n9} via biometric"}'],
		['loan-origination', '', 'INFO  [LoanWorkflow] Loan application LA{n7} submitted for HOME_LOAN, amount INR {amt}'],
		['treasury-fx', '', '{iso} | INFO | treasury-fx | FX deal FX{n8} booked USD/INR 83.{n2} notional 2,500,000'],
		['upi-switch', 'e', '{"level":"ERROR","service":"upi-switch","message":"NPCI switch timeout for NOVA{n14} after 30000ms (RespCode BT)"}'],
		['core-banking', 'wn', 'WARN 4221 --- [hikari-pool-2] c.z.h.p.HikariPool : Connection pool at 82% (164/200)'],
		['insurance-claims', '', 'claims INFO Claim CLM{n7} registered for policy POL{n7}, amount INR {amt}']
	];
	const rint = (n) => Math.floor(Math.random() * n);
	function fillMock(t) {
		return t.replace(/\{n(\d+)\}/g, (_, k) => { let s = String(1 + rint(9)); for (let i = 1; i < k; i++) s += rint(10); return s; })
			.replace('{amt}', nfIn.format(rint(90000) + 99) + '.00').replace('{vpa}', 'abcdefghij'[rint(10)] + '****' + rint(10) + rint(10) + '@okaxis')
			.replace(/\{iso\}/g, new Date(Date.now() - rint(60000)).toISOString());
	}
	function mockLine(i) {
		const m = MOCK_LINES[i % 7 === 3 ? 11 : rint(MOCK_LINES.length)];
		return { svc: m[0], cls: m[1], text: fillMock(m[2]) };
	}
	const npciError = () => fillMock(pickOne(['NPCI switch timeout for NOVA{n14} after 30000ms (RespCode BT)', 'UPI transaction NOVA{n14} deemed failed: no response from NPCI',
		'ReqPay NOVA{n14} timed out at NPCI gateway, RTT 4{n3}ms', 'NPCI switch timeout for NOVA{n14} after 30000ms (RespCode BT)']));
	const pickOne = (a) => a[rint(a.length)];
	const mmss = (sec) => { sec = Math.max(0, Math.round(sec)); return (sec >= 60 ? Math.floor(sec / 60) + 'm ' : '') + (sec % 60) + 's'; };
	const clock = (ms) => new Date(ms).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' });

	const P = {
		el: null, open: false, idx: 0, token: 0, autoplay: true, captions: true,
		elapsed: 0, last: 0, startedAt: 0, raf: 0
	};

	// =====================================================================
	// SERVICE MAP (the "world" canvas)
	// =====================================================================
	const NODES = [
		{ id: 'mobile-banking-api', label: 'Mobile banking', x: .05, y: .2 },
		{ id: 'netbanking-portal', label: 'Net banking', x: .05, y: .52 },
		{ id: 'atm-switch', label: 'ATM switch', x: .05, y: .84 },
		{ id: 'upi-switch', label: 'UPI switch', x: .34, y: .26, r: 1.3 },
		{ id: 'payment-gateway', label: 'Payment gateway', x: .3, y: .6, r: 1.1 },
		{ id: 'card-auth', label: 'Card auth', x: .36, y: .9 },
		{ id: 'fraud-engine', label: 'Fraud engine', x: .58, y: .1 },
		{ id: 'core-banking', label: 'Core banking', x: .62, y: .54, r: 1.55 },
		{ id: 'insurance-claims', label: 'Insurance', x: .6, y: .95 },
		{ id: 'kyc-service', label: 'KYC', x: .9, y: .2 },
		{ id: 'loan-origination', label: 'Loans', x: .95, y: .55 },
		{ id: 'treasury-fx', label: 'Treasury FX', x: .88, y: .88 }
	];
	const EDGES = [['mobile-banking-api', 'upi-switch'], ['mobile-banking-api', 'payment-gateway'], ['netbanking-portal', 'upi-switch'], ['netbanking-portal', 'payment-gateway'],
		['netbanking-portal', 'core-banking'], ['atm-switch', 'card-auth'], ['payment-gateway', 'card-auth'], ['upi-switch', 'core-banking'], ['payment-gateway', 'core-banking'],
		['card-auth', 'core-banking'], ['upi-switch', 'fraud-engine'], ['payment-gateway', 'fraud-engine'], ['fraud-engine', 'core-banking'], ['kyc-service', 'core-banking'],
		['loan-origination', 'core-banking'], ['loan-origination', 'kyc-service'], ['treasury-fx', 'core-banking'], ['insurance-claims', 'core-banking'], ['card-auth', 'insurance-claims']];
	const byId = Object.fromEntries(NODES.map((n) => [n.id, n]));
	// mock per-minute traffic at peak hour; dot density on the map follows these weights
	const RATES = { 'upi-switch': 9840, 'core-banking': 7210, 'mobile-banking-api': 5630, 'payment-gateway': 4120, 'netbanking-portal': 3380, 'card-auth': 2960,
		'fraud-engine': 1870, 'atm-switch': 1240, 'kyc-service': 610, 'loan-origination': 380, 'treasury-fx': 210, 'insurance-claims': 160 };
	NODES.forEach((n) => { n.r = n.r || 1; n.rate = RATES[n.id]; n.shown = n.rate; n.err = 0; n.hit = 0; n.errHit = 0; });
	const edges = EDGES.map(([a, b]) => ({ a: byId[a], b: byId[b] }));

	const W = { // world state
		cam: { s: .9, fx: .5, fy: .5, ox: .5, oy: .54, a: 0 },
		to: { s: .9, fx: .5, fy: .5, ox: .5, oy: .54, a: 0 },
		packets: [], queue: [], incident: null, healAt: 0, critAt: 0
	};
	function camera(t) { Object.assign(W.to, t); }
	const CAM = {
		hidden: { a: 0, s: .9, fx: .5, fy: .5, ox: .5, oy: .54 },
		center: { a: 1, s: .86, fx: .5, fy: .5, ox: .5, oy: .59 },
		faint: { a: .2, s: .95, fx: .5, fy: .5, ox: .5, oy: .56 },
		focus: { a: 1, s: 1.55, fx: byId[FOCUS].x, fy: byId[FOCUS].y, ox: .36, oy: .46 },
		left: { a: .38, s: .72, fx: .5, fy: .5, ox: .27, oy: .55 },
		dim: { a: .08, s: 1.1, fx: .5, fy: .5, ox: .5, oy: .55 },
		back: { a: .16, s: .85, fx: .5, fy: .5, ox: .5, oy: .5 }
	};

	function nodeState(n) { return W.incident === n.id ? 'crit' : 'ok'; }
	const COLOR = { ok: [61, 220, 151], warn: [255, 181, 71], crit: [255, 77, 94] };
	const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;

	/** Synthetic traffic: dots per second proportional to each service's mock rate. */
	const TOTAL_RATE = Object.values(RATES).reduce((a, b) => a + b, 0);
	function traffic(now, dt) {
		const perSec = 34; // dots on screen per second, all services together
		for (const n of NODES) {
			const out = W.incident === n.id;
			const lambda = perSec * (n.rate / TOTAL_RATE) * (out ? 1.7 : 1) * dt / 1000;
			let k = lambda > 1 ? Math.round(lambda) : (Math.random() < lambda ? 1 : 0);
			while (k-- > 0) W.queue.push({ n, err: Math.random() < (out ? .45 : .006), at: now + Math.random() * 120 });
		}
		W.queue.sort((a, b) => a.at - b.at);
	}
	/** Per-node labels: mock rate with a little jitter, refreshed about once a second. */
	function jitterRates(now) {
		if (W.jitterAt && now - W.jitterAt < 900) return;
		W.jitterAt = now;
		for (const n of NODES) {
			const out = W.incident === n.id;
			n.shown = Math.round(n.rate * (out ? 1.4 : 1) * (0.94 + Math.random() * .12));
			n.err = out ? Math.round(M.errMin * (0.93 + Math.random() * .14)) : Math.random() < .25 ? 1 + rint(4) : 0;
		}
	}
	function spawn(q, now) {
		const n = q.n;
		const incoming = edges.filter((e) => e.b === n);
		let from;
		if (incoming.length && Math.random() < .85) from = incoming[Math.floor(Math.random() * incoming.length)].a;
		else from = { x: n.x - .1, y: n.y + (Math.random() - .5) * .12, ghost: true }; // from customers
		W.packets.push({ from, to: n, t0: now, dur: 900 + Math.random() * 700, err: q.err });
	}

	// Glow sprites, rendered once per colour: far cheaper than a radial gradient per node per frame.
	const SPRITES = {};
	function sprite(key, col) {
		if (SPRITES[key]) return SPRITES[key];
		const c = document.createElement('canvas'), n = 128;
		c.width = c.height = n;
		const g = c.getContext('2d'), gr = g.createRadialGradient(n / 2, n / 2, 0, n / 2, n / 2, n / 2);
		gr.addColorStop(0, rgba(col, .55)); gr.addColorStop(.35, rgba(col, .18)); gr.addColorStop(1, rgba(col, 0));
		g.fillStyle = gr; g.fillRect(0, 0, n, n);
		return (SPRITES[key] = c);
	}
	const PACKET_COLORS = { err: COLOR.crit, warn: COLOR.warn, ok: [143, 220, 255] };
	const TRAIL = [0, .03, .06]; // trail offsets along the path, newest first

	function drawWorld(ctx, w, h, now, dt) {
		const c = W.cam;
		const k = 1 - Math.exp(-dt / 320); // frame-rate independent easing
		for (const key of Object.keys(W.to)) c[key] += (W.to[key] - c[key]) * k;
		jitterRates(now);
		if (c.a < .01) {
			if (!W.blank) { ctx.clearRect(0, 0, w, h); W.blank = true; }
			W.packets.length = 0; W.queue.length = 0;
			return;
		}
		traffic(now, dt);
		W.blank = false;
		ctx.clearRect(0, 0, w, h);
		ctx.globalAlpha = c.a;
		const boxW = Math.min(w * .82, h * 1.75), boxH = Math.min(h * .56, boxW * .52);
		const narrow = w < 700; // phones: map below the text, centred
		const ox = narrow ? .5 : c.ox, oy = narrow ? Math.min(.8, c.oy + .2) : c.oy;
		const sx = boxW * c.s, sy = boxH * c.s, bx = ox * w - c.fx * sx, by = oy * h - c.fy * sy;
		for (const n of NODES) { n.sx = bx + n.x * sx; n.sy = by + n.y * sy; }
		const unit = Math.max(.7, Math.min(1.4, boxW / 1100)) * Math.sqrt(c.s);

		// edges: two batched paths instead of one gradient per edge
		ctx.lineWidth = 1.2 * unit;
		ctx.strokeStyle = 'rgba(120,150,255,.16)';
		ctx.beginPath();
		const hot = [];
		for (const e of edges) {
			if (W.incident && (e.a.id === W.incident || e.b.id === W.incident)) { hot.push(e); continue; }
			ctx.moveTo(e.a.sx, e.a.sy); ctx.lineTo(e.b.sx, e.b.sy);
		}
		ctx.stroke();
		if (hot.length) {
			ctx.strokeStyle = 'rgba(255,77,94,.4)';
			ctx.lineWidth = 1.8 * unit;
			ctx.setLineDash([6 * unit, 7 * unit]); ctx.lineDashOffset = -now / 30;
			ctx.beginPath();
			for (const e of hot) { ctx.moveTo(e.a.sx, e.a.sy); ctx.lineTo(e.b.sx, e.b.sy); }
			ctx.stroke();
			ctx.setLineDash([]);
		}

		// packets: advance, then draw batched by colour and trail step
		while (W.queue.length && W.queue[0].at <= now) spawn(W.queue.shift(), now);
		if (W.queue.length > 300) W.queue.splice(0, W.queue.length - 300);
		const batches = { err: [], warn: [], ok: [] };
		let keep = 0;
		for (const p of W.packets) {
			const t = (now - p.t0) / p.dur;
			if (t >= 1) {
				p.to.hit = now;
				if (p.err) p.to.errHit = now;
				continue;
			}
			W.packets[keep++] = p;
			const e = t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
			const x1 = p.from.ghost ? bx + p.from.x * sx : p.from.sx, y1 = p.from.ghost ? by + p.from.y * sy : p.from.sy;
			batches[p.err ? 'err' : (W.incident && p.to.id === W.incident ? 'warn' : 'ok')].push([x1, y1, p.to.sx - x1, p.to.sy - y1, e, p.err]);
		}
		W.packets.length = keep;
		if (keep > 220) W.packets.splice(0, keep - 220);
		for (const key of ['ok', 'warn', 'err']) {
			const list = batches[key];
			if (!list.length) continue;
			TRAIL.forEach((off, s) => {
				ctx.fillStyle = rgba(PACKET_COLORS[key], s === 0 ? .95 : s === 1 ? .5 : .22);
				ctx.beginPath();
				for (const [x1, y1, dx, dy, e, err] of list) {
					const ee = Math.max(0, e - off), r = (err ? 2.8 : 2.1) * unit * (1 - s * .18);
					const x = x1 + dx * ee, y = y1 + dy * ee;
					ctx.moveTo(x + r, y); ctx.arc(x, y, r, 0, Math.PI * 2);
				}
				ctx.fill();
			});
		}

		// nodes
		for (const n of NODES) {
			const x = n.sx, y = n.sy;
			const st = nodeState(n), col = COLOR[st];
			n.st = st;
			const r = 15 * n.r * unit;
			const hit = Math.max(0, 1 - (now - n.hit) / 700), eh = Math.max(0, 1 - (now - n.errHit) / 900);
			const hr = r * (3.4 + hit * 1.1);
			ctx.globalAlpha = c.a * Math.min(1, .55 + hit * .45 + (st === 'crit' ? .35 : 0));
			ctx.drawImage(sprite(st, col), x - hr, y - hr, hr * 2, hr * 2);
			ctx.globalAlpha = c.a;
			if (st === 'crit') {
				ctx.lineWidth = 2 * unit;
				for (let i = 0; i < 3; i++) {
					const ph = ((now / 1600) + i / 3) % 1;
					ctx.strokeStyle = rgba(col, (1 - ph) * .55);
					ctx.beginPath(); ctx.arc(x, y, r * (1.2 + ph * 3.4), 0, Math.PI * 2); ctx.stroke();
				}
			}
			if (W.healAt && n.id === FOCUS) {
				const ph = (now - W.healAt) / 1800;
				if (ph < 1) {
					ctx.strokeStyle = rgba(COLOR.ok, (1 - ph) * .8);
					ctx.lineWidth = 3 * unit;
					ctx.beginPath(); ctx.arc(x, y, r * (1.2 + ph * 6), 0, Math.PI * 2); ctx.stroke();
				}
			}
			ctx.fillStyle = '#0a0e1a';
			ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
			ctx.lineWidth = (2 + hit * 1.5) * unit;
			ctx.strokeStyle = rgba(eh > 0 && st !== 'crit' ? COLOR.crit : col, .85 + hit * .15);
			ctx.stroke();
			ctx.fillStyle = rgba(col, .9);
			ctx.beginPath(); ctx.arc(x, y, r * (.32 + hit * .12), 0, Math.PI * 2); ctx.fill();
		}
		// labels: one font switch per pass
		ctx.textAlign = 'center';
		ctx.font = `600 ${Math.round(12.5 * unit)}px Inter, system-ui, sans-serif`;
		for (const n of NODES) {
			ctx.fillStyle = n.st === 'crit' ? '#ffb3ba' : 'rgba(238,240,247,.86)';
			ctx.fillText(n.label, n.sx, n.sy + 15 * n.r * unit + 18 * unit);
		}
		ctx.font = `500 ${Math.round(11 * unit)}px Inter, system-ui, sans-serif`;
		for (const n of NODES) {
			ctx.fillStyle = n.st === 'crit' ? 'rgba(255,140,150,.9)' : 'rgba(154,163,184,.75)';
			ctx.fillText(n.st === 'crit' ? `${nfmt.format(n.err)} errors / min` : `${nfmt.format(n.shown)} / min${n.err ? ' · ' + n.err + ' err' : ''}`, n.sx, n.sy + 15 * n.r * unit + 33 * unit);
		}
		ctx.globalAlpha = 1;
	}

	// =====================================================================
	// AMBIENT BACKGROUND
	// =====================================================================
	const dots = Array.from({ length: 70 }, () => ({ x: Math.random(), y: Math.random(), z: .25 + Math.random() * .75, vx: (Math.random() - .5) * .00004, vy: -.00002 - Math.random() * .00005 }));
	const LINK = 16000;
	function drawAmbient(ctx, w, h, dt) {
		ctx.clearRect(0, 0, w, h);
		const px = new Float32Array(dots.length * 2);
		dots.forEach((d, i) => {
			d.x = (d.x + d.vx * dt + 1) % 1; d.y = (d.y + d.vy * dt * d.z + 1) % 1;
			px[i * 2] = d.x * w; px[i * 2 + 1] = d.y * h;
		});
		// links in three opacity bands, one stroke each
		const bands = [[], [], []];
		for (let i = 0; i < dots.length; i++) {
			const xi = px[i * 2], yi = px[i * 2 + 1];
			for (let j = i + 1; j < dots.length; j++) {
				const dx = xi - px[j * 2], dy = yi - px[j * 2 + 1], d2 = dx * dx + dy * dy;
				if (d2 < LINK) bands[d2 < LINK * .33 ? 0 : d2 < LINK * .66 ? 1 : 2].push(i, j);
			}
		}
		ctx.lineWidth = 1;
		[.07, .045, .02].forEach((a, b) => {
			const l = bands[b];
			if (!l.length) return;
			ctx.strokeStyle = `rgba(139,156,255,${a})`;
			ctx.beginPath();
			for (let k = 0; k < l.length; k += 2) { ctx.moveTo(px[l[k] * 2], px[l[k] * 2 + 1]); ctx.lineTo(px[l[k + 1] * 2], px[l[k + 1] * 2 + 1]); }
			ctx.stroke();
		});
		// dots in three depth bands
		[[.25, .5], [.5, .75], [.75, 1.01]].forEach(([lo, hi]) => {
			ctx.fillStyle = `rgba(200,212,255,${hi * .42})`;
			ctx.beginPath();
			dots.forEach((d, i) => { if (d.z >= lo && d.z < hi) { const r = d.z * 1.4; ctx.moveTo(px[i * 2] + r, px[i * 2 + 1]); ctx.arc(px[i * 2], px[i * 2 + 1], r, 0, Math.PI * 2); } });
			ctx.fill();
		});
	}

	// =====================================================================
	// SMALL ANIMATION HELPERS
	// =====================================================================
	/** Splits text into words that rise in one after another. */
	function words(text, base, step, cls) {
		return text.split(' ').map((w, i) => `<span class="w rv${cls ? ' ' + cls : ''}" style="--d:${base + i * step}">${esc(w)}${i < text.split(' ').length - 1 ? ' ' : ''}</span>`).join('');
	}
	function countUp(el, to, ms, fmt, delay) {
		if (!el) return;
		fmt = fmt || ((v) => nfmt.format(Math.round(v)));
		const start = performance.now() + (delay || 0);
		const tick = (t) => {
			if (!el.isConnected) return;
			const p = Math.min(1, Math.max(0, (t - start) / ms));
			const e = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);
			const txt = fmt(to * e);
			if (txt !== el.textContent) el.textContent = txt;
			if (p < 1) requestAnimationFrame(tick);
		};
		el.textContent = fmt(0);
		requestAnimationFrame(tick);
	}
	async function typeText(el, text, cps, alive) {
		el.classList.add('pm-caret');
		el.textContent = '';
		const step = reduce ? text.length : 2;
		for (let i = 0; i <= text.length; i += step) {
			if (!alive()) return;
			el.textContent = text.slice(0, i);
			await sleep(1000 / cps * step);
		}
		el.textContent = text;
		el.classList.remove('pm-caret');
	}
	const ICON = {
		bars: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16M4 12h10M4 18h13"/></svg>',
		eye: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 9 6 9 6a17 17 0 0 1-3 3.6M6.6 6.6C4.3 8.1 3 12 3 12s4 6 9 6a9 9 0 0 0 4.4-1.1"/></svg>',
		phone: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/></svg>',
		coin: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M9 8h6M9 11h6M13 8c2 0 2 6-4 6l5 4"/></svg>',
		parse: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 4H6a2 2 0 0 0-2 2v4l-2 2 2 2v4a2 2 0 0 0 2 2h2M16 4h2a2 2 0 0 1 2 2v4l2 2-2 2v4a2 2 0 0 1-2 2h-2"/></svg>',
		live: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12h4l3-8 4 16 3-8h4"/></svg>',
		bell: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 8 3 8H3s3-1 3-8M10 20a2 2 0 0 0 4 0"/></svg>',
		rupee: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 4h12M6 9h12M14 4c4 0 4 10-6 10l7 7"/></svg>',
		brain: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.8 4.6L18.5 9l-4.7 1.4L12 15l-1.8-4.6L5.5 9l4.7-1.4z"/><path d="M19 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/></svg>',
		doc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h5"/></svg>'
	};
	const LOGO = '<svg class="pm-logo" viewBox="0 0 84 84"><rect x="1" y="1" width="82" height="82" rx="22"/><path d="M14 44h12l6-16 10 30 8-22 4 8h16"/></svg>';

	// =====================================================================
	// SCENES
	// =====================================================================
	const SCENES = [
		{
			id: 'intro', chapter: 'Welcome', dur: 10000, cam: 'hidden', ambient: 1,
			caption: () => `${BRAND} runs on 12 core services that write ${M.perDayShort} log lines a day. Log Analyzer turns that noise into decisions, in real time.`,
			html: () => `<div class="pm-intro" style="display:flex;flex-direction:column">
				<div class="rv pop" style="--d:0">${LOGO}</div>
				<div class="pm-kicker rv" style="--d:200">${esc(BRAND)} · BFSI observability</div>
				<div class="pm-h1">${words('See every failure.', 350, 90)}<br><span class="grad">${words('Know what it costs.', 750, 90)}</span></div>
				<p class="pm-lead rv" style="--d:1300">Real-time log intelligence for banking: detect, diagnose and resolve incidents before customers notice.</p>
				<div class="pm-chips">
					<span class="pm-chip rv" style="--d:1700"><i></i><b>12</b> core banking services</span>
					<span class="pm-chip rv" style="--d:1850"><b data-count="${M.perDay}" data-fmt="short">0</b> events / day</span>
					<span class="pm-chip rv" style="--d:2000"><b data-count="${M.perMin}">0</b> events / min at peak</span>
					<span class="pm-chip rv" style="--d:2150"><b>13</b> log formats, incl. Siebel CRM</span>
				</div></div>`,
			enter(el) { countAll(el, 2000); }
		},
		{
			id: 'problem', chapter: 'The problem', dur: 13000, cam: 'hidden', ambient: 1,
			caption: () => `Every day that's ${M.perDayShort} lines in a dozen formats. The one line that matters hides in the noise, teams hear about outages from customers, and nobody can say what an outage costs.`,
			html: () => `<div class="pm-split">
				<div>
					<div class="pm-kicker rv" style="--d:0">Every single day</div>
					<div class="pm-num rv" style="--d:150"><span data-count="${M.perDay}">0</span></div>
					<div class="pm-h3 rv" style="--d:300;margin-top:14px;color:#cfd5e6">log lines from 12 services, in 13 formats</div>
					<div class="pm-pains">
						<div class="pm-pain rv from-l" style="--d:900">${ICON.eye}Failures hide in the noise</div>
						<div class="pm-pain rv from-l" style="--d:1150">${ICON.phone}Teams find out from customers, 20+ minutes late</div>
						<div class="pm-pain rv from-l" style="--d:1400">${ICON.coin}No one knows what an outage costs</div>
					</div>
				</div>
				<div class="pm-card pm-rain rv from-r" style="--d:400"><div class="pm-scan"></div><div class="pm-rain-track pm-mono">${rainLines()}</div></div>
			</div>`,
			enter(el) { countAll(el, 300, 2800); }
		},
		{
			id: 'live', chapter: 'Live system', dur: 13000, cam: 'center', ambient: .4,
			caption: () => `This is ${BRAND} at peak hour. Every moving dot is a transaction flowing between services, and each one glows by health.`,
			html: () => `<div class="pm-hud tl">
					<div class="pm-kicker rv" style="--d:0;margin:0">Live service map</div>
					<div class="pm-h2 rv" style="--d:150">${words('One view of every service.', 200, 60)}</div>
					<div class="pm-legend rv" style="--d:900"><span><i style="background:#3ddc97"></i>Healthy</span><span><i style="background:#ffb547"></i>Degraded</span><span><i style="background:#ff4d5e"></i>Failing</span></div>
				</div>
				<div class="pm-hud tr" style="flex-direction:row;gap:12px">
					<div class="pm-card pm-stat rv" style="--d:700"><span class="pm-label">Events / min</span><b data-live="rate">${nfmt.format(M.perMin)}</b>${spark()}</div>
					<div class="pm-card pm-stat rv" style="--d:850"><span class="pm-label">Error rate</span><b data-count="${M.errorRate}" data-fmt="pct2">0%</b><small>across all services</small></div>
					<div class="pm-card pm-stat rv" style="--d:1000"><span class="pm-label">Health score</span><b data-count="${M.health}" data-fmt="dec1">0</b><small>out of 100</small></div>
				</div>`,
			enter(el) { countAll(el, 900, 1800); }
		},
		{
			id: 'incident', chapter: 'Incident', dur: 11000, cam: 'focus', ambient: .2,
			caption: () => `${clock(T.onset)}. NPCI round-trip time jumps from 38 ms to 4 seconds, and UPI payments start timing out. Watch it unfold at 8× speed.`,
			html: () => `<div class="pm-hud tr" style="gap:14px">
					<div class="pm-kicker rv" style="--d:0;margin:0;color:var(--pm-red)">● Outage in progress</div>
					<div class="pm-h2 rv" style="--d:150;text-align:right"><span class="pm-glitch">UPI switch is failing</span></div>
					<div class="rv pm-h3" style="--d:300;color:var(--pm-dim)"><span class="pm-time" id="pmIncClock">${clock(T.onset)}</span> · <span class="pm-lapse">▶▶ 8× time-lapse</span></div>
					<div class="pm-incstats rv" style="--d:450">
						<div class="pm-card pm-stat"><span class="pm-label">UPI errors / min</span><b class="red" id="pmErrMin">0</b><small id="pmIncState">payments timing out at NPCI</small></div>
						<div class="pm-card pm-gauge"><svg viewBox="0 0 120 70"><path class="trk" d="M10 64a50 50 0 0 1 100 0"/><path class="val" id="pmGauge" d="M10 64a50 50 0 0 1 100 0"/></svg><b id="pmRate">0%</b><span class="pm-label">failure rate</span></div>
					</div>
					<div class="pm-feed" id="pmFeed"></div>
				</div>`,
			enter(el, alive) {
				P.el.classList.add('alarm');
				W.incident = FOCUS;
				P.el.classList.remove('shake'); void P.el.offsetWidth; P.el.classList.add('shake');
				const start = performance.now();
				const tick = () => {
					if (!alive()) return;
					const real = performance.now() - start, story = Math.min(real * 8, 6 * 60000);
					const p = Math.min(1, real / 5500), e = 1 - Math.pow(1 - p, 3);
					setText($('pmIncClock'), clock(T.onset + story));
					setText($('pmErrMin'), nfmt.format(Math.round(M.errMin * e)));
					setText($('pmRate'), (M.failRate * e).toFixed(1) + '%');
					const g = $('pmGauge'); if (g) g.style.strokeDashoffset = String(158 * (1 - e * M.failRate / 40));
					if (real > 4800 && !tick.alerted) { tick.alerted = true; setText($('pmIncState'), `Alert raised at ${clock(T.detected)}, ${M.ttd} s after onset`); }
					if (real < 9000) requestAnimationFrame(tick);
				};
				requestAnimationFrame(tick);
				const feed = () => {
					const box = $('pmFeed');
					if (!box || !alive()) return;
					const row = document.createElement('div');
					row.className = 'pm-feed-row pm-mono';
					row.innerHTML = `<span>${clock(T.onset + (performance.now() - start) * 8)}</span>${esc(npciError())}`;
					box.prepend(row);
					while (box.children.length > 4) box.lastChild.remove();
					setTimeout(feed, 420 + Math.random() * 380);
				};
				setTimeout(feed, 700);
			}
		},
		{
			id: 'detect', chapter: 'Detection', dur: 11000, cam: 'left', ambient: .3,
			caption: () => `Nobody was watching a screen. ${M.ttd} seconds after onset the rule “${M.rule}” fired, opened ${M.incId} and paged the payments-upi on-call engineer.`,
			html: () => `<div class="pm-detect">
					<div class="pm-ttd rv pop" style="--d:300"><svg viewBox="0 0 120 120"><circle class="trk" cx="60" cy="60" r="52"/><circle class="val" cx="60" cy="60" r="52"/></svg><div><b data-count="${M.ttd}" data-fmt="int">0</b><span>seconds to detect</span></div></div>
					<div>
						<div class="pm-kicker rv" style="--d:100;color:var(--pm-red)">Detected automatically</div>
						<div class="pm-card pm-alert rv from-r" style="--d:250">
							<div class="pm-alert-top"><span class="pm-sev CRITICAL">CRITICAL</span><span class="pm-inc-id pm-mono">${M.incId}</span><span class="pm-inc-id">· opened ${clock(T.detected)}</span></div>
							<h3>UPI switch: NPCI timeouts failing customer payments</h3>
							<p>Rule “${esc(M.rule)}” fired: ${nfmt.format(M.ruleErrors)} errors / ${nfmt.format(M.ruleEvents)} events (${M.failRate}%) in 5 min. Paged payments-upi on-call.</p>
							<div class="pm-rows">
								<div><span class="pm-label">Owner</span><b>payments-upi</b></div>
								<div><span class="pm-label">Blast radius</span><b>UPI · 4 PSP apps</b></div>
								<div><span class="pm-label">Severity</span><b style="color:var(--pm-red)">P1</b></div>
							</div>
						</div>
						<div class="pm-push"><div class="pm-push-ic">!</div><div><b>P1 · ${esc(M.oncall)} (on-call)</b><small>${M.incId} UPI switch: NPCI timeouts failing customer payments</small><div class="pm-acked" id="pmAcked"></div></div><em>now</em></div>
					</div>
				</div>`,
			async enter(el, alive) {
				countAll(el, 600, 1400);
				await sleep(4300);
				if (!alive()) return;
				const a = $('pmAcked');
				if (a) { a.innerHTML = `✓ Acknowledged by <b>${esc(M.oncall)}</b> · ${mmss((T.acked - T.detected) / 1000)} after the page`; a.classList.add('on'); }
			}
		},
		{
			id: 'impact', chapter: 'Business impact', dur: 12000, cam: 'dim', ambient: .3,
			caption: () => `And we know what it costs while it is happening: ${inr(M.value)} of payments at risk, ${nfmt.format(M.failedTxns)} failed transactions, and the monthly SLA budget burning by the minute.`,
			html: () => `<div class="pm-impact">
					<div>
						<div class="pm-kicker rv" style="--d:0;color:var(--pm-amber)">Payments at risk · this incident</div>
						<div class="pm-money grad-red rv" style="--d:150" data-count="${M.value}" data-fmt="inr">₹0</div>
						<div class="pm-proj rv" style="--d:900">Bleeding <b>${inr(M.perMinLoss)} every minute</b> · about <b>${inr(M.perMinLoss * 60)}</b> an hour if left unresolved</div>
					</div>
					<div class="pm-tiles">
						<div class="pm-card pm-tile rv from-r" style="--d:400"><span class="pm-label">Failed transactions</span><b data-count="${M.failedTxns}">0</b><small>UPI payments that did not complete</small></div>
						<div class="pm-card pm-tile rv from-r" style="--d:550"><span class="pm-label">Customers affected</span><b data-count="${M.customers}">0</b><small>counted once per VPA, retries removed</small></div>
						<div class="pm-card pm-tile rv from-r" style="--d:700"><span class="pm-label">UPI SLA budget used · this month</span><b><span data-count="${M.budgetAfter}" data-from="${M.budgetBefore}" data-fmt="pctint">${M.budgetBefore}%</span></b><small>was ${M.budgetBefore}% before this incident · target 99.95%</small><div class="pm-burn"><i class="was" style="transform:scaleX(${M.budgetBefore / 100})"></i><i data-burn="${M.budgetAfter}"></i></div></div>
					</div>
				</div>`,
			enter(el) {
				countAll(el, 300, 2600);
				setTimeout(() => { const b = el.querySelector('[data-burn]'); if (b) b.style.transform = `scaleX(${b.dataset.burn / 100})`; }, 50);
			}
		},
		{
			id: 'diagnose', chapter: 'Diagnosis', dur: 15000, cam: 'back', ambient: .3,
			caption: () => `The NLP engine collapses ${nfmt.format(M.patternCount)} errors into one pattern, links the spike to a firewall change made three minutes earlier, and proposes the fix.`,
			html: () => `<div class="pm-diag">
					<div class="pm-card rv from-l" style="--d:0">
						<div class="pm-label">${ICON.brain.replace('<svg', '<svg style="width:14px;height:14px;vertical-align:-2px;margin-right:6px"')}Error pattern</div>
						<div class="pm-tpl pm-mono" id="pmTpl"></div>
						<div class="pm-facts">
							<div class="rv" style="--d:900"><span class="pm-label">Errors grouped</span><b data-count="${M.patternCount}" data-delay="1000">0</b></div>
							<div class="rv" style="--d:1050"><span class="pm-label">Patterns</span><b>3 → 1</b></div>
							<div class="rv" style="--d:1200"><span class="pm-label">Confidence</span><b data-count="94" data-fmt="pctint" data-delay="1200">0%</b></div>
						</div>
						<div class="pm-change" id="pmChange">
							<div class="pm-change-ic">⇄</div>
							<div><span class="pm-label" style="color:var(--pm-amber)">Correlated change · 3 min before onset</span><b>CHG-10293 · Firewall rule update on the NPCI DMZ</b><small>${clock(T.change)} · network-ops · approved CAB-4471</small></div>
						</div>
						<div class="pm-src">NLP engine · BFSI failure playbook · personal data redacted</div>
					</div>
					<div class="pm-card rv from-r" style="--d:200">
						<div class="pm-label">Probable root cause</div>
						<div class="pm-cause" id="pmCause"></div>
						<div class="pm-label" style="margin-top:14px">Recovery plan</div>
						<ol class="pm-steps" id="pmSteps"></ol>
					</div>
				</div>`,
			async enter(el, alive) {
				countAll(el, 0, 1400);
				await sleep(500);
				await typeHtml($('pmTpl'), 'NPCI switch timeout for <*> after <*>ms (RespCode BT)', alive);
				if (!alive()) return;
				const ch = $('pmChange'); if (ch) ch.classList.add('on');
				await sleep(700);
				await typeText($('pmCause'), M.rootCause, 75, alive);
				const ol = $('pmSteps');
				if (!ol || !alive()) return;
				ol.innerHTML = M.steps.map((s, i) => `<li><i>${i + 1}</i><span>${esc(s)}</span></li>`).join('');
				for (const li of ol.children) { await sleep(reduce ? 0 : 480); if (!alive()) return; li.classList.add('on'); li.querySelector('i').textContent = '✓'; }
			}
		},
		{
			id: 'resolve', chapter: 'Resolution', dur: 14000, cam: 'faint', ambient: .3,
			caption: () => `${M.oncall} fails UPI over to the secondary NPCI link and rolls back the change. Payments recover in ${mmss((T.resolved - T.onset) / 1000)}, against a ${M.oldMttr}-minute average before.`,
			html: () => `<div class="pm-resolve" id="pmResolve" style="display:flex;flex-direction:column;align-items:center">
					<svg class="pm-check" viewBox="0 0 96 96"><circle cx="48" cy="48" r="45"/><path d="M30 49l12 12 24-26"/></svg>
					<div class="pm-h2 rv" style="--d:0" id="pmResTitle">Failing over to the secondary NPCI link…</div>
					<div class="pm-track rv" style="--d:250">
						<div class="pm-track-fill" id="pmFill"></div>
						<div class="pm-step" style="--c:#ff4d5e" data-s="0"><i>1</i><b>Detected</b><small>${clock(T.detected)}</small></div>
						<div class="pm-step" style="--c:#ffb547" data-s="1"><i>2</i><b>Acknowledged</b><small>${clock(T.acked)} · ${esc(M.oncall)}</small></div>
						<div class="pm-step" style="--c:#8b9cff" data-s="2"><i>3</i><b>Diagnosed</b><small>${clock(T.diagnosed)} · NLP</small></div>
						<div class="pm-step" style="--c:#3ddc97" data-s="3"><i>4</i><b>Resolved</b><small>${clock(T.resolved)} · ${esc(M.oncall)}</small></div>
					</div>
					<div class="pm-compare rv" style="--d:500">
						<div class="pm-cmp-row"><span>Average recovery before</span><div class="pm-cmp-bar old"><i></i></div><b>${M.oldMttr} min</b></div>
						<div class="pm-cmp-row"><span>With Log Analyzer</span><div class="pm-cmp-bar new"><i></i></div><b class="grad-green">${mmss((T.resolved - T.onset) / 1000)}</b></div>
						<div class="pm-cmp-foot"><b class="grad-green" data-count="${M.mttrGain}" data-fmt="pctint" data-delay="900">0%</b> faster recovery · <b data-count="${M.customers}" data-delay="900">0</b> payments auto-reconciled, none lost</div>
					</div>
				</div>`,
			async enter(el, alive) {
				const step = (k) => {
					const s = el.querySelector(`[data-s="${k}"]`);
					if (!s) return;
					s.classList.add('on'); s.querySelector('i').textContent = k === 0 ? '!' : '✓';
					$('pmFill').style.transform = `scaleX(${k / 3})`;
				};
				for (let k = 0; k < 3; k++) { await sleep(k === 0 ? 400 : 650); if (!alive()) return; step(k); }
				await sleep(900); if (!alive()) return;
				step(3);
				W.incident = null;
				W.healAt = performance.now();
				P.el.classList.remove('alarm');
				const fl = P.el.querySelector('.pm-flash');
				fl.classList.remove('heal'); void fl.offsetWidth; fl.classList.add('heal');
				el.querySelector('#pmResolve').classList.add('done');
				$('pmResTitle').innerHTML = '<span class="grad-green">UPI payments restored</span>';
				alignGradients(el);
				el.querySelector('.pm-compare').classList.add('go');
				countAll(el, 0, 1600);
			}
		},
		{
			id: 'value', chapter: 'Why it matters', dur: 13000, cam: 'hidden', ambient: 1,
			caption: () => 'From log line to business decision, in one place: universal parsing, live streaming, incidents, business impact, root-cause analysis and executive reports.',
			html: () => {
				const f = [['parse', 'Universal parser', '13 log formats incl. Siebel CRM, auto-detected'], ['live', 'Live stream', 'Every event on screen the moment it is stored'],
					['bell', 'Incident workflow', 'Alert rules, on-call routing, acknowledge, resolve'], ['rupee', 'Business impact & SLA', 'Money at risk, customers, error budgets'],
					['brain', 'Root-cause analysis', 'NLP patterns, change correlation, BFSI playbook'], ['doc', 'Executive reports', 'One-click multi-page PDF for leadership']];
				return `<div class="pm-value" style="display:flex;flex-direction:column;align-items:center">
					<div class="pm-kicker rv" style="--d:0">Why it matters</div>
					<div class="pm-h2">${words('From log line to', 100, 60)} <span class="grad">${words('business decision.', 340, 70)}</span></div>
					<div class="pm-kpis">
						<div class="rv pop" style="--d:500"><b data-count="${M.ttd}" data-fmt="sec" data-delay="600">0 s</b><span>mean time to detect</span></div>
						<div class="rv pop" style="--d:620"><b data-count="${M.mttrGain}" data-fmt="pctint" data-delay="700">0%</b><span>faster recovery</span></div>
						<div class="rv pop" style="--d:740"><b data-count="${M.annualSaving}" data-fmt="inr" data-delay="800">₹0</b><span>downtime cost avoided / year*</span></div>
					</div>
					<div class="pm-feats">${f.map((x, i) => `<div class="pm-card pm-feat rv tilt" style="--d:${900 + i * 110}">${ICON[x[0]]}<b>${x[1]}</b><small>${x[2]}</small></div>`).join('')}</div>
					<div class="pm-tech rv" style="--d:1800">Java 17 · Spring Boot · H2 · Server-Sent Events · NLP · Chart.js &nbsp;·&nbsp; <span style="text-transform:none;letter-spacing:0">*projected from 9 P1 outages a year</span></div>
				</div>`;
			},
			enter(el) { countAll(el, 0, 1800); }
		},
		{
			id: 'outro', chapter: 'Thank you', dur: Infinity, cam: 'hidden', ambient: 1,
			caption: () => 'Thank you. Questions?',
			html: () => `<div class="pm-outro" style="display:flex;flex-direction:column;align-items:center">
					<div class="rv pop" style="--d:0">${LOGO}</div>
					<div class="pm-h1">${words('Thank you.', 200, 120)}</div>
					<p class="pm-lead rv" style="--d:700;margin-left:auto;margin-right:auto">See every failure. Know what it costs. Fix it in minutes.</p>
					<div class="pm-url pm-mono rv" style="--d:900">${esc(location.host + location.pathname.replace(/\/$/, ''))}</div>
					<div class="pm-actions rv" style="--d:1100"><button class="pm-cta" data-pm="replay">Replay</button><button class="pm-cta primary" data-pm="exit">Explore the live dashboard</button></div>
				</div>`
		}
	];

	function rainLines() {
		const out = [];
		for (let i = 0; i < 42; i++) out.push(mockLine(i));
		const html = out.map((l) => `<div class="${l.cls}"><b>${esc(l.svc)}</b>${esc(l.text)}</div>`).join('');
		return html + html; // twice, for a seamless loop
	}
	async function typeHtml(el, tpl, alive) {
		if (!el) return;
		el.classList.add('pm-caret');
		const parts = tpl.split(/(<\*>)/);
		let html = '';
		for (const part of parts) {
			if (part === '<*>') { html += '<span class="ph">&lt;*&gt;</span>'; el.innerHTML = html; await sleep(90); continue; }
			for (let i = 0; i < part.length; i += 2) {
				if (!alive()) return;
				el.innerHTML = html + esc(part.slice(0, i + 2));
				await sleep(reduce ? 0 : 24);
			}
			html += esc(part);
		}
		el.innerHTML = html;
		el.classList.remove('pm-caret');
	}
	/** Animates every [data-count] in a scene: data-fmt picks the format, data-from the start value. */
	function countAll(el, delay, ms) {
		const F = {
			int: (v) => nfmt.format(Math.round(v)), short: short, pct2: (v) => v.toFixed(2) + '%', dec1: (v) => v.toFixed(1),
			pctint: (v) => Math.round(v) + '%', inr: (v) => inr(v), sec: (v) => Math.round(v) + ' s'
		};
		el.querySelectorAll('[data-count]').forEach((n, k) => {
			if (n.dataset.counted) return;
			n.dataset.counted = '1';
			const fmt = F[n.dataset.fmt] || F.int, from = Number(n.dataset.from || 0), to = Number(n.dataset.count);
			countUp(n, to - from, ms || 2000, (v) => fmt(from + v), Number(n.dataset.delay || delay || 0) + k * 60);
		});
	}
	function spark() {
		let d = 'M0 22', y = 14;
		for (let x = 6; x <= 120; x += 6) { y = Math.max(4, Math.min(24, y + (Math.random() - .45) * 7)); d += ` L${x} ${y.toFixed(1)}`; }
		return `<svg class="pm-spark" viewBox="0 0 120 28" preserveAspectRatio="none"><path d="${d}"/></svg>`;
	}

	// =====================================================================
	// ENGINE
	// =====================================================================
	function build() {
		const el = document.createElement('div');
		el.className = 'pm';
		el.setAttribute('role', 'dialog');
		el.setAttribute('aria-label', 'Presentation');
		el.innerHTML = `
			<div class="pm-aurora"><i></i><i></i><i></i></div>
			<canvas class="pm-ambient"></canvas><canvas class="pm-world"></canvas>
			<div class="pm-flash"></div>
			<div class="pm-top">
				<div class="pm-brand"><svg viewBox="0 0 84 84"><rect x="1" y="1" width="82" height="82" rx="22" fill="rgba(139,156,255,.14)" stroke="rgba(139,156,255,.5)"/><path d="M14 44h12l6-16 10 30 8-22 4 8h16" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/></svg>Log Analyzer<span>${esc(BRAND)}</span></div>
				<div class="pm-chapter"><b id="pmNum">01</b><span class="pm-chap" id="pmChap">Welcome</span></div>
				<div class="pm-ctrls">
					<span class="pm-live" title="Scripted story with sample figures"><i></i>DEMO</span>
					<span class="pm-clock" id="pmClock">0:00</span>
					<button class="pm-btn" data-pm="prev" title="Previous (←)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 6l-6 6 6 6"/></svg></button>
					<button class="pm-btn" data-pm="play" title="Pause or resume auto-play (Space)"><svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg></button>
					<button class="pm-btn" data-pm="next" title="Next (→)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg></button>
					<button class="pm-btn on" data-pm="captions" title="Narration captions (N)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 12h3M13 12h4M7 15h7"/></svg></button>
					<button class="pm-btn" data-pm="full" title="Full screen (F)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg></button>
					<button class="pm-btn" data-pm="exit" title="Exit (Esc)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
				</div>
			</div>
			<div class="pm-stage" id="pmStage"></div>
			<div class="pm-bottom">
				<div class="pm-caption" id="pmCaption"></div>
				<div class="pm-segs" id="pmSegs">${SCENES.map((s, i) => `<div class="pm-seg" data-go="${i}"><span>${String(i + 1).padStart(2, '0')} · ${s.chapter}</span><i></i></div>`).join('')}</div>
				<div class="pm-hint"><kbd>←</kbd> <kbd>→</kbd> navigate · <kbd>Space</kbd> pause · <kbd>N</kbd> captions · <kbd>F</kbd> full screen · <kbd>Esc</kbd> exit</div>
			</div>
			<div class="pm-curtain" id="pmCurtain"><div>${esc(BRAND)} · Log Analyzer</div></div>`;
		document.body.appendChild(el);
		el.addEventListener('click', (ev) => {
			const b = ev.target.closest('[data-pm]');
			const seg = ev.target.closest('[data-go]');
			if (seg) return go(Number(seg.dataset.go));
			if (!b) return;
			const a = b.dataset.pm;
			if (a === 'prev') go(P.idx - 1);
			if (a === 'next') go(P.idx + 1);
			if (a === 'play') toggleAutoplay();
			if (a === 'captions') toggleCaptions();
			if (a === 'full') toggleFull();
			if (a === 'exit') close();
			if (a === 'replay') replay();
		});
		P.el = el;
		P.ctxA = el.querySelector('.pm-ambient').getContext('2d');
		P.ctxW = el.querySelector('.pm-world').getContext('2d', { desynchronized: true });
		P.clockEl = el.querySelector('#pmClock');
	}

	function resize() {
		const dpr = Math.min(1.5, window.devicePixelRatio || 1);
		const set = (c, ratio) => {
			c.width = Math.round(innerWidth * ratio); c.height = Math.round(innerHeight * ratio);
			c.getContext('2d').setTransform(ratio, 0, 0, ratio, 0, 0);
		};
		set(P.el.querySelector('.pm-world'), dpr);
		set(P.el.querySelector('.pm-ambient'), 1); // soft dots: full resolution is wasted here
	}

	function frame(t) {
		if (!P.open) return;
		const dt = Math.min(50, t - (P.last || t));
		P.last = t;
		const scene = SCENES[P.idx];
		drawAmbient(P.ctxA, innerWidth, innerHeight, dt);
		drawWorld(P.ctxW, innerWidth, innerHeight, t, dt);
		// progress & auto-advance (transform only, no layout)
		if (P.autoplay) P.elapsed += dt;
		const dur = scene.dur;
		const ready = !scene.ready || scene.ready();
		let pct = dur === Infinity ? 0 : Math.min(1, P.elapsed / dur);
		if (!ready) pct = Math.min(pct, .92);
		if (P.fill) P.fill.style.transform = `scaleX(${pct.toFixed(4)})`;
		if (P.autoplay && dur !== Infinity && ((P.elapsed >= dur && ready) || P.elapsed >= (scene.maxDur || Infinity))) {
			if (scene.id === 'incident' && ready && P.elapsed < dur + 1200 && !P.holdOnce) { P.holdOnce = true; } // tiny beat after detection
			else go(P.idx + 1);
		}
		// text updates a few times a second, only when they change
		if (!P.liveTick || t - P.liveTick > 300) {
			P.liveTick = t;
			const sec = Math.floor((Date.now() - P.startedAt) / 1000);
			setText(P.clockEl, Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0'));
			if (!P.rateAt || t - P.rateAt > 1100) {
				P.rateAt = t;
				setText(P.el.querySelector('[data-live="rate"]'), nfmt.format(Math.round(M.perMin * (0.97 + Math.random() * .06))));
			}
		}
		P.raf = requestAnimationFrame(frame);
	}
	function setText(el, v) { if (el && el.textContent !== v) el.textContent = v; }

	async function go(i) {
		i = Math.max(0, Math.min(SCENES.length - 1, i));
		const token = ++P.token;
		const alive = () => P.token === token && P.open;
		const scene = SCENES[i];
		P.idx = i;
		P.elapsed = 0;
		P.holdOnce = false;
		// chrome
		$('pmNum').textContent = String(i + 1).padStart(2, '0');
		const chap = $('pmChap'); chap.textContent = scene.chapter; chap.style.animation = 'none'; void chap.offsetWidth; chap.style.animation = '';
		P.el.querySelectorAll('.pm-seg').forEach((s, k) => { s.classList.toggle('done', k < i); s.classList.toggle('cur', k === i); s.querySelector('i').style.transform = k < i ? 'scaleX(1)' : 'scaleX(0)'; });
		P.fill = P.el.querySelector('.pm-seg.cur i');
		P.el.querySelector('.pm-ambient').style.opacity = scene.ambient;
		camera(CAM[scene.cam]);
		const outage = ['incident', 'detect', 'impact', 'diagnose'].includes(scene.id);
		if (outage) { W.incident = FOCUS; P.el.classList.add('alarm'); }
		else if (scene.id !== 'resolve') { W.incident = null; P.el.classList.remove('alarm'); }
		else { W.incident = FOCUS; P.el.classList.add('alarm'); } // resolve scene heals it on cue
		// old scene out
		const stage = $('pmStage');
		stage.querySelectorAll('.pm-scene').forEach((old) => { old.classList.add('out'); setTimeout(() => old.remove(), 650); });
		if (scene.before) { try { await scene.before(); } catch (e) { /* render with what we have */ } }
		if (!alive()) return;
		const el = document.createElement('div');
		el.className = 'pm-scene';
		el.innerHTML = scene.html();
		stage.appendChild(el);
		alignGradients(el);
		requestAnimationFrame(() => requestAnimationFrame(() => el.classList.add('in')));
		const cap = $('pmCaption');
		cap.innerHTML = `<p>${esc(scene.caption())}</p>`;
		if (scene.enter) { try { await scene.enter(el, alive); } catch (e) { /* keep presenting */ } }
	}

	/** Each animated word paints its own slice of one continuous gradient across the whole phrase. */
	function alignGradients(root) {
		root.querySelectorAll('.grad, .grad-green, .grad-red').forEach((g) => {
			const gr = g.getBoundingClientRect();
			g.querySelectorAll('.w').forEach((w) => {
				const r = w.getBoundingClientRect();
				w.style.backgroundSize = `${gr.width}px 100%`;
				w.style.backgroundPosition = `${gr.left - r.left}px 0`; // the gradient is horizontal: only x matters
			});
		});
	}

	async function open(startAt) {
		if (P.open) return;
		if (!P.el) build();
		P.open = true;
		window.LA_PRESENTING = true;
		P.startedAt = Date.now();
		P.last = 0;
		setStory();
		W.incident = null; W.healAt = 0;
		document.body.classList.add('pm-open');
		P.el.classList.add('on');
		$('pmCurtain').classList.remove('gone');
		resize();
		if (innerWidth < 600 && P.captions) toggleCaptions(); // phones: no room for narration
		P.raf = requestAnimationFrame(frame);
		await sleep(reduce ? 0 : 700);
		$('pmCurtain').classList.add('gone');
		go(startAt || 0);
	}

	function close() {
		if (!P.open) return;
		P.open = false;
		P.token++;
		cancelAnimationFrame(P.raf);
		P.el.classList.remove('on', 'alarm');
		document.body.classList.remove('pm-open');
		if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
		setTimeout(() => { if (!P.open) $('pmStage').innerHTML = ''; }, 650);
		if (location.hash === '#present') history.replaceState(null, '', location.pathname + location.search);
		window.LA_PRESENTING = false;
		if (LA.refreshAll) LA.refreshAll(); // catch up on what the live dashboard missed
	}

	function replay() {
		W.incident = null; W.healAt = 0;
		P.el.classList.remove('alarm');
		P.startedAt = Date.now();
		setStory();
		go(0);
	}
	function toggleAutoplay() {
		P.autoplay = !P.autoplay;
		const b = P.el.querySelector('[data-pm="play"]');
		b.innerHTML = P.autoplay
			? '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>'
			: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 5l12 7-12 7z"/></svg>';
		b.classList.toggle('on', !P.autoplay);
	}
	function toggleCaptions() {
		P.captions = !P.captions;
		$('pmCaption').classList.toggle('off', !P.captions);
		P.el.querySelector('[data-pm="captions"]').classList.toggle('on', P.captions);
	}
	function toggleFull() {
		if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
		else if (P.el.requestFullscreen) P.el.requestFullscreen().catch(() => {});
	}

	// =====================================================================
	// WIRING
	// =====================================================================
	document.addEventListener('keydown', (ev) => {
		const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName) || ev.target.isContentEditable;
		if (!P.open) {
			if (!typing && (ev.key === 'p' || ev.key === 'P') && !ev.ctrlKey && !ev.metaKey && !ev.altKey) { ev.preventDefault(); open(); }
			return;
		}
		if (['ArrowRight', 'PageDown', 'Enter'].includes(ev.key)) { ev.preventDefault(); go(P.idx + 1); }
		else if (['ArrowLeft', 'PageUp'].includes(ev.key)) { ev.preventDefault(); go(P.idx - 1); }
		else if (ev.key === ' ') { ev.preventDefault(); toggleAutoplay(); }
		else if (ev.key === 'n' || ev.key === 'N') toggleCaptions();
		else if (ev.key === 'f' || ev.key === 'F') toggleFull();
		else if (ev.key === 'Home') replay();
		else if (ev.key === 'Escape' && !document.fullscreenElement) close();
	}, true);
	window.addEventListener('resize', () => { if (P.open) resize(); });
	const btn = $('presentBtn');
	if (btn) btn.addEventListener('click', () => { open(); if (P.el && P.el.requestFullscreen && !reduce) P.el.requestFullscreen().catch(() => {}); });
	window.LA_PRESENT = { open, close, go, state: P };
	if (location.hash === '#present') setTimeout(() => open(), 900);
})();
