/* Log Analyzer – presentation mode.
 * A full-screen, scripted story that runs on the live system: it streams real log events into an animated
 * service map, injects a real UPI outage, waits for the alert rules to open an incident, shows its business
 * impact and NLP diagnosis, then acknowledges and resolves it. Keys: ← → Space N F Esc. */
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
	const post = (path, body) => LA.api(path, { method: 'POST', headers: body ? { 'Content-Type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
	const inr = (v) => (LA.business ? LA.business.inr(v) : '₹' + Math.round(v));
	const mmss = (sec) => { sec = Math.max(0, Math.round(sec)); return (sec >= 60 ? Math.floor(sec / 60) + 'm ' : '') + (sec % 60) + 's'; };
	const clock = (ms) => new Date(ms).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' });

	const P = {
		el: null, open: false, idx: 0, token: 0, autoplay: true, captions: true,
		elapsed: 0, last: 0, startedAt: 0, raf: 0, es: null,
		data: {}, // base data and story state (incident, analysis…)
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
	NODES.forEach((n) => { n.r = n.r || 1; n.events = []; n.errors = []; n.hit = 0; n.errHit = 0; });
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

	function nodeState(n, now) {
		if (W.incident === n.id) return 'crit';
		while (n.events.length && n.events[0] < now - 60000) n.events.shift();
		while (n.errors.length && n.errors[0] < now - 60000) n.errors.shift();
		if (n.errors.length >= 3 && n.errors.length / Math.max(1, n.events.length) >= .1) return 'warn';
		return 'ok';
	}
	const COLOR = { ok: [61, 220, 151], warn: [255, 181, 71], crit: [255, 77, 94] };
	const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;

	/** Queue packets for newly stored logs: each one travels along an edge into its service. */
	function onLogs(records) {
		const now = performance.now();
		const feed = document.getElementById('pmFeed');
		if (feed) {
			records.filter((r) => r.service === FOCUS && (r.level === 'ERROR' || r.level === 'FATAL')).slice(-3).forEach((r) => {
				const row = document.createElement('div');
				row.className = 'pm-feed-row pm-mono';
				row.innerHTML = `<span>${esc(new Date(r.timestamp).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' }))}</span>${esc(r.message.slice(0, 120))}`;
				feed.prepend(row);
				while (feed.children.length > 4) feed.lastChild.remove();
			});
		}
		records.slice(-60).forEach((r, i) => {
			const n = byId[r.service];
			if (!n) return;
			W.queue.push({ n, err: r.level === 'ERROR' || r.level === 'FATAL', at: now + (i / Math.min(60, records.length)) * 1800 + Math.random() * 200 });
		});
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
		const wall0 = Date.now();
		if (c.a < .01) {
			if (!W.blank) { ctx.clearRect(0, 0, w, h); W.blank = true; }
			// map hidden: still count each event against its service, just don't draw it
			for (const p of W.packets) { p.to.events.push(wall0); if (p.err) p.to.errors.push(wall0); }
			W.packets.length = 0;
			while (W.queue.length && W.queue[0].at <= now) { const q = W.queue.shift(); q.n.events.push(wall0); if (q.err) q.n.errors.push(wall0); }
			return;
		}
		W.blank = false;
		ctx.clearRect(0, 0, w, h);
		ctx.globalAlpha = c.a;
		const boxW = Math.min(w * .82, h * 1.75), boxH = Math.min(h * .56, boxW * .52);
		const narrow = w < 700; // phones: map below the text, centred
		const ox = narrow ? .5 : c.ox, oy = narrow ? Math.min(.8, c.oy + .2) : c.oy;
		const sx = boxW * c.s, sy = boxH * c.s, bx = ox * w - c.fx * sx, by = oy * h - c.fy * sy;
		for (const n of NODES) { n.sx = bx + n.x * sx; n.sy = by + n.y * sy; }
		const unit = Math.max(.7, Math.min(1.4, boxW / 1100)) * Math.sqrt(c.s);
		const wall = Date.now();

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
				p.to.hit = now; p.to.events.push(wall);
				if (p.err) { p.to.errHit = now; p.to.errors.push(wall); }
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
			const st = nodeState(n, wall), col = COLOR[st];
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
			const em = n.events.length, er = n.errors.length;
			ctx.fillStyle = n.st === 'crit' ? 'rgba(255,140,150,.9)' : 'rgba(154,163,184,.75)';
			ctx.fillText(n.st === 'crit' ? `${er} errors / min` : `${em} / min${er ? ' · ' + er + ' err' : ''}`, n.sx, n.sy + 15 * n.r * unit + 33 * unit);
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
			caption: () => `Our banking platform runs on ${P.data.services} services writing thousands of log lines every hour. Log Analyzer turns that noise into decisions.`,
			html: () => `<div class="pm-intro" style="display:flex;flex-direction:column">
				<div class="rv pop" style="--d:0">${LOGO}</div>
				<div class="pm-kicker rv" style="--d:200">${esc(BRAND)} · BFSI observability</div>
				<div class="pm-h1">${words('See every failure.', 350, 90)}<br><span class="grad">${words('Know what it costs.', 750, 90)}</span></div>
				<p class="pm-lead rv" style="--d:1300">Real-time log intelligence for banking: detect, diagnose and resolve incidents before customers notice.</p>
				<div class="pm-chips">
					<span class="pm-chip rv" style="--d:1700"><i></i><b>Live</b> data, not slides</span>
					<span class="pm-chip rv" style="--d:1850"><b>${P.data.services}</b> banking services</span>
					<span class="pm-chip rv" style="--d:2000"><b data-count="${P.data.total}">0</b> events in 24h</span>
					<span class="pm-chip rv" style="--d:2150"><b>12</b> log formats understood</span>
				</div></div>`,
			enter(el) { countUp(el.querySelector('[data-count]'), P.data.total, 2200, null, 2000); }
		},
		{
			id: 'problem', chapter: 'The problem', dur: 13000, cam: 'hidden', ambient: 1,
			caption: () => `In the last 24 hours that's ${nfmt.format(P.data.total)} lines in a dozen formats. Failures hide in this noise, teams hear about outages from customers, and nobody can say what an outage costs.`,
			html: () => `<div class="pm-split">
				<div>
					<div class="pm-kicker rv" style="--d:0">Every single day</div>
					<div class="pm-num rv" style="--d:150"><span data-count>0</span></div>
					<div class="pm-h3 rv" style="--d:300;margin-top:14px;color:#cfd5e6">log lines from ${P.data.services} services, in 12 formats</div>
					<div class="pm-pains">
						<div class="pm-pain rv from-l" style="--d:900">${ICON.eye}Failures hide in the noise</div>
						<div class="pm-pain rv from-l" style="--d:1150">${ICON.phone}Teams find out from customers</div>
						<div class="pm-pain rv from-l" style="--d:1400">${ICON.coin}No one knows what an outage costs</div>
					</div>
				</div>
				<div class="pm-card pm-rain rv from-r" style="--d:400"><div class="pm-rain-track pm-mono">${rainLines()}</div></div>
			</div>`,
			enter(el) { countUp(el.querySelector('[data-count]'), P.data.total, 2600, null, 300); }
		},
		{
			id: 'live', chapter: 'Live system', dur: 13000, cam: 'center', ambient: .4,
			caption: () => `This is ${BRAND} right now. Every moving dot is a real log event arriving from a service; colours show each service's health.`,
			html: () => `<div class="pm-hud tl">
					<div class="pm-kicker rv" style="--d:0;margin:0">Live service map</div>
					<div class="pm-h2 rv" style="--d:150">${words('One view of every service.', 200, 60)}</div>
					<div class="pm-legend rv" style="--d:900"><span><i style="background:#3ddc97"></i>Healthy</span><span><i style="background:#ffb547"></i>Degraded</span><span><i style="background:#ff4d5e"></i>Failing</span></div>
				</div>
				<div class="pm-hud tr" style="flex-direction:row;gap:12px">
					<div class="pm-card pm-stat rv" style="--d:700"><span class="pm-label">Events / min</span><b data-live="rate">—</b><small>streamed over SSE</small></div>
					<div class="pm-card pm-stat rv" style="--d:850"><span class="pm-label">Error rate · 24h</span><b>${P.data.errorRate.toFixed(2)}%</b><small>across all services</small></div>
					<div class="pm-card pm-stat rv" style="--d:1000"><span class="pm-label">Health score</span><b data-count="${P.data.health}">0</b><small>out of 100</small></div>
				</div>`,
			enter(el) {
				const h = el.querySelector('[data-count]');
				countUp(h, P.data.health, 1800, (v) => v.toFixed(1), 1000);
			}
		},
		{
			id: 'incident', chapter: 'Incident', dur: 9000, maxDur: 40000, cam: 'focus', ambient: .2,
			ready: () => !!P.data.incident,
			caption: () => 'Now let’s break something. We inject a real UPI switch outage: NPCI timeouts start failing customer payments.',
			html: () => `<div class="pm-hud tr" style="gap:14px">
					<div class="pm-kicker rv" style="--d:0;margin:0;color:var(--pm-red)">● Outage in progress</div>
					<div class="pm-h2 rv" style="--d:150;text-align:right"><span class="pm-glitch">UPI switch is failing</span></div>
					<div class="rv pm-h3" style="--d:300;color:var(--pm-dim)"><span class="pm-time" id="pmIncClock">—</span> · payments timing out at NPCI</div>
					<div class="pm-card pm-stat pm-meter rv" style="--d:500"><span class="pm-label">UPI errors in the last minute</span><b data-live="upiErr">0</b><small id="pmIncState">waiting for the alert rules…</small></div>
					<div class="pm-feed" id="pmFeed"></div>
				</div>`,
			async enter(el, alive) {
				document.querySelector('.pm').classList.add('alarm');
				W.incident = FOCUS;
				W.critAt = performance.now();
				const t = setInterval(() => { const c = $('pmIncClock'); if (!c) return clearInterval(t); c.textContent = clock(Date.now()); }, 250);
				if (P.data.incident) { setState('Alert raised: incident ' + LA.business.incId(P.data.incident)); return; }
				try {
					P.data.triggeredAt = Date.now();
					await post(`/api/simulator/incident?service=${FOCUS}&minutes=3&burst=2`);
					for (let i = 0; i < 14 && alive(); i++) {
						await sleep(i === 0 ? 1600 : 1800);
						const list = await post('/api/incidents/evaluate?force=true');
						const found = list.items.find((x) => x.service === FOCUS);
						if (found) {
							P.data.incident = found;
							setState('Alert raised · ' + LA.business.incId(found) + ' opened');
							return;
						}
					}
					setState('Still waiting for the alert rules…');
				} catch (e) { setState('Could not start the outage: ' + e.message); }
				function setState(s) { const x = $('pmIncState'); if (x) x.textContent = s; }
			}
		},
		{
			id: 'detect', chapter: 'Detection', dur: 11000, cam: 'left', ambient: .3,
			caption: () => {
				const i = P.data.incident;
				return `Nobody was watching a screen. The rule “${i ? i.rule : 'UPI payment failures'}” fired, opened an incident and paged the ${i ? i.owner : 'payments-upi'} on-call team.`;
			},
			html: () => {
				const i = P.data.incident || { id: 0, severity: 'CRITICAL', title: 'UPI switch / NPCI connectivity on upi-switch', owner: 'payments-upi', rule: 'UPI payment failures', openedAt: Date.now(), peakErrorRate: 0 };
				const d = P.data.detail;
				const opened = d && d.timeline && d.timeline[0] ? d.timeline[0].text.split('\n')[0] : `Rule "${i.rule}" fired.`;
				return `<div class="pm-detect">
					<div></div>
					<div>
						<div class="pm-kicker rv" style="--d:100;color:var(--pm-red)">Detected automatically</div>
						<div class="pm-card pm-alert rv from-r" style="--d:250">
							<div class="pm-alert-top"><span class="pm-sev ${esc(i.severity)}">${esc(i.severity)}</span><span class="pm-inc-id pm-mono">${LA.business.incId(i)}</span><span class="pm-inc-id">· opened ${clock(i.openedAt)}</span></div>
							<h3>${esc(i.title)}</h3>
							<p>${esc(opened)}</p>
							<div class="pm-rows">
								<div><span class="pm-label">Owner</span><b>${esc(i.owner)}</b></div>
								<div><span class="pm-label">Peak errors</span><b>${i.peakErrorRate}%</b></div>
								<div><span class="pm-label">Rules run</span><b>every 20 s</b></div>
							</div>
						</div>
						<div class="pm-push"><div class="pm-push-ic">!</div><div><b>${esc(i.severity)} · ${esc(i.owner)} on-call</b><small>${LA.business.incId(i)} ${esc(i.title)}</small><div class="pm-acked" id="pmAcked"></div></div><em>now</em></div>
					</div>
				</div>`;
			},
			async before() { await loadDetail(); },
			async enter(el, alive) {
				const i = P.data.incident;
				if (!i) return;
				if (i.status === 'OPEN') {
					await sleep(4600);
					if (!alive()) return;
					try { P.data.detail = P.data.incident = await post(`/api/incidents/${i.id}/ack`); } catch (e) { return; }
				}
				const d = P.data.detail || i, a = $('pmAcked');
				if (a && d.acknowledgedAt) { a.innerHTML = `✓ Acknowledged by <b>${esc(d.acknowledgedBy)}</b> · ${mmss((d.acknowledgedAt - d.openedAt) / 1000)} after opening`; a.classList.add('on'); }
			}
		},
		{
			id: 'impact', chapter: 'Business impact', dur: 12000, cam: 'dim', ambient: .3,
			caption: () => 'And we know what it costs, while it is happening: money at risk, failed payments, customers affected, and how fast the SLA error budget is burning.',
			html: () => {
				const im = (P.data.detail && P.data.detail.impact) || { valueAtRisk: 0, failedTransactions: 0, customersAffected: 0 };
				const mins = P.data.detail ? Math.max(1, (P.data.detail.windowTo - P.data.detail.windowFrom) / 60000) : 5;
				const perHour = im.valueAtRisk / mins * 60;
				const sla = (P.data.impact.sla || []).find((s) => s.service === FOCUS) || { budgetUsed: 0, target: 99.5 };
				return `<div class="pm-impact">
					<div>
						<div class="pm-kicker rv" style="--d:0;color:var(--pm-amber)">Value at risk · this incident</div>
						<div class="pm-money grad-red rv" style="--d:150" data-money="${im.valueAtRisk}">₹0</div>
						<div class="pm-proj rv" style="--d:900">If left unresolved: about <b>${esc(inr(perHour))}</b> every hour</div>
					</div>
					<div class="pm-tiles">
						<div class="pm-card pm-tile rv from-r" style="--d:400"><span class="pm-label">Failed transactions</span><b data-count="${im.failedTransactions}">0</b><small>customer payments that did not complete</small></div>
						<div class="pm-card pm-tile rv from-r" style="--d:550"><span class="pm-label">Customers affected</span><b data-count="${im.customersAffected}">0</b><small>counted once per account, card or VPA</small></div>
						<div class="pm-card pm-tile rv from-r" style="--d:700"><span class="pm-label">UPI SLA budget used · 24h</span><b data-count="${Math.round(sla.budgetUsed)}" data-suffix="%">0%</b><small>target ${sla.target}% availability</small><div class="pm-burn"><i data-burn="${Math.min(100, sla.budgetUsed)}"></i></div></div>
					</div>
				</div>`;
			},
			async before() { await loadDetail(); try { P.data.impact = await LA.api('/api/impact?range=24h'); } catch (e) { /* keep base */ } },
			enter(el) {
				const m = el.querySelector('[data-money]');
				countUp(m, Number(m.dataset.money), 2600, (v) => inr(v), 300);
				el.querySelectorAll('[data-count]').forEach((n, k) => countUp(n, Number(n.dataset.count), 1800, (v) => nfmt.format(Math.round(v)) + (n.dataset.suffix || ''), 500 + k * 150));
				setTimeout(() => { const b = el.querySelector('[data-burn]'); if (b) b.style.transform = `scaleX(${b.dataset.burn / 100})`; }, 50);
			}
		},
		{
			id: 'diagnose', chapter: 'Diagnosis', dur: 15000, cam: 'back', ambient: .3,
			caption: () => 'The NLP engine groups the errors into one pattern, matches it to the BFSI failure playbook and proposes recovery steps, with personal data redacted.',
			html: () => `<div class="pm-diag">
					<div class="pm-card rv from-l" style="--d:0">
						<div class="pm-label">${ICON.brain.replace('<svg', '<svg style="width:14px;height:14px;vertical-align:-2px;margin-right:6px"')}Error pattern</div>
						<div class="pm-tpl pm-mono" id="pmTpl"></div>
						<div class="pm-facts">
							<div class="rv" style="--d:900"><span class="pm-label">Seen · 24h</span><b id="pmOcc">—</b></div>
							<div class="rv" style="--d:1050"><span class="pm-label">Similar</span><b id="pmSim">—</b></div>
							<div class="rv" style="--d:1200"><span class="pm-label">Severity</span><b id="pmSev">—</b></div>
						</div>
						<div class="pm-label" style="margin-top:20px">Similar errors (TF-IDF)</div>
						<div class="pm-sims" id="pmSims"></div>
						<div class="pm-src" id="pmSrc">Analysing…</div>
					</div>
					<div class="pm-card rv from-r" style="--d:200">
						<div class="pm-label">Probable root cause</div>
						<div class="pm-cause" id="pmCause"></div>
						<div class="pm-label" style="margin-top:14px">Recovery</div>
						<ol class="pm-steps" id="pmSteps"></ol>
					</div>
				</div>`,
			async enter(el, alive) {
				let a = P.data.analysis, ctx = P.data.context;
				try {
					if (!a) {
						const p = new URLSearchParams({ service: FOCUS, levels: 'ERROR,FATAL', sort: 'timestamp', dir: 'desc', page: 0, size: 1, range: '1h' });
						const page = await LA.api('/api/logs?' + p);
						if (page.content.length) {
							const id = page.content[0].id;
							[ctx, a] = await Promise.all([LA.api(`/api/ai/logs/${id}/context`), post(`/api/ai/logs/${id}`)]);
							P.data.analysis = a; P.data.context = ctx; P.data.diagnosedAt = Date.now();
						}
					}
				} catch (e) { /* show what we have */ }
				if (!alive()) return;
				const tpl = (ctx && ctx.template) || 'NPCI switch timeout for <*> after <*>ms (RespCode BT)';
				$('pmTpl').innerHTML = '';
				await sleep(500);
				await typeHtml($('pmTpl'), tpl, alive);
				if (!alive()) return;
				if (ctx) {
					countUp($('pmOcc'), ctx.occurrences24h || 0, 1200); countUp($('pmSim'), (ctx.similar || []).length, 900);
					$('pmSims').innerHTML = (ctx.similar || []).slice(0, 3).map((x, k) => `<div class="pm-sim" style="animation-delay:${k * 160}ms"><span class="pm-mono">${x.score}%</span><em>${esc(x.service)}</em>${esc(x.message.slice(0, 110))}</div>`).join('') || '<div class="pm-sim">No other similar errors in the last 24 hours.</div>';
				}
				$('pmSev').textContent = a ? a.severity : 'HIGH';
				$('pmSrc').textContent = a ? (a.source === 'GEMINI' ? 'Gemini · ' + a.model : 'Offline NLP engine · BFSI failure playbook') + ' · personal data redacted' : 'Offline NLP engine · BFSI failure playbook';
				await typeText($('pmCause'), (a && a.rootCause) || 'NPCI or the UPI switch link is slow or unreachable, so payments time out and are marked deemed-failed.', 70, alive);
				const steps = ((a && a.recoverySteps) || ['Check NPCI status bulletins and the UPI switch link.', 'Fail over to the secondary NPCI link.', 'Reconcile deemed transactions with a status check.']).slice(0, 4);
				const ol = $('pmSteps');
				if (!ol || !alive()) return;
				ol.innerHTML = steps.map((s, i) => `<li><i>${i + 1}</i><span>${esc(s)}</span></li>`).join('');
				for (const li of ol.children) { await sleep(reduce ? 0 : 520); if (!alive()) return; li.classList.add('on'); li.querySelector('i').textContent = '✓'; }
			}
		},
		{
			id: 'resolve', chapter: 'Resolution', dur: 13000, cam: 'faint', ambient: .3,
			caption: () => 'The on-call engineer acknowledges, fails over and resolves. Every step lands on the incident timeline, and MTTA and MTTR are tracked automatically.',
			html: () => `<div class="pm-resolve" id="pmResolve" style="display:flex;flex-direction:column;align-items:center">
					<svg class="pm-check" viewBox="0 0 96 96"><circle cx="48" cy="48" r="45"/><path d="M30 49l12 12 24-26"/></svg>
					<div class="pm-h2 rv" style="--d:0" id="pmResTitle">Working the incident…</div>
					<div class="pm-track rv" style="--d:250">
						<div class="pm-track-fill" id="pmFill"></div>
						<div class="pm-step on" style="--c:#ff4d5e" data-s="0"><i>!</i><b>Detected</b><small>${P.data.incident ? clock(P.data.incident.openedAt) : '—'}</small></div>
						<div class="pm-step" style="--c:#ffb547" data-s="1"><i>2</i><b>Acknowledged</b><small></small></div>
						<div class="pm-step" style="--c:#8b9cff" data-s="2"><i>3</i><b>Diagnosed</b><small></small></div>
						<div class="pm-step" style="--c:#3ddc97" data-s="3"><i>4</i><b>Resolved</b><small></small></div>
					</div>
					<div class="pm-mttr" id="pmMttr"></div>
				</div>`,
			async enter(el, alive) {
				const step = (k, text) => {
					const s = el.querySelector(`[data-s="${k}"]`);
					if (!s) return;
					s.classList.add('on'); s.querySelector('i').textContent = '✓'; s.querySelector('small').textContent = text || '';
					$('pmFill').style.transform = `scaleX(${k / 3})`;
				};
				const inc = P.data.incident;
				if (!inc) { $('pmResTitle').textContent = 'No incident to resolve'; return; }
				let d = P.data.detail;
				if (d && d.status === 'RESOLVED') return finish(d);
				await sleep(1200); if (!alive()) return;
				if (!d || !d.acknowledgedAt) { try { d = await post(`/api/incidents/${inc.id}/ack`); } catch (e) { /* continue */ } }
				step(1, d && d.acknowledgedAt ? clock(d.acknowledgedAt) + ' · ' + d.acknowledgedBy : '');
				await sleep(1500); if (!alive()) return;
				step(2, P.data.diagnosedAt ? clock(P.data.diagnosedAt) + ' · NLP' : 'NLP playbook');
				try { await post('/api/simulator/incident/stop'); } catch (e) { /* ignore */ }
				await sleep(1500); if (!alive()) return;
				try { d = await post(`/api/incidents/${inc.id}/resolve`, { note: 'Failed over to the secondary NPCI link (live presentation).' }); } catch (e) { /* show anyway */ }
				P.data.detail = d || P.data.detail;
				finish(d);

				function finish(det) {
					W.incident = null;
					W.healAt = performance.now();
					const pm = document.querySelector('.pm');
					pm.classList.remove('alarm');
					const fl = pm.querySelector('.pm-flash');
					fl.classList.remove('heal'); void fl.offsetWidth; fl.classList.add('heal');
					step(1, det && det.acknowledgedAt ? clock(det.acknowledgedAt) + ' · ' + det.acknowledgedBy : '');
					step(2, P.data.diagnosedAt ? clock(P.data.diagnosedAt) + ' · NLP' : 'NLP playbook');
					step(3, det && det.resolvedAt ? clock(det.resolvedAt) + ' · ' + det.resolvedBy : '');
					$('pmFill').style.transform = 'scaleX(1)';
					el.querySelector('#pmResolve').classList.add('done');
					$('pmResTitle').innerHTML = '<span class="grad-green">UPI payments restored</span>';
					if (det) {
						const mtta = det.acknowledgedAt ? (det.acknowledgedAt - det.openedAt) / 1000 : null;
						const mttr = det.resolvedAt ? (det.resolvedAt - det.openedAt) / 1000 : null;
						$('pmMttr').innerHTML = `<div class="pm-card pm-stat rv pend" style="--d:0"><span class="pm-label">Time to acknowledge</span><b>${mtta == null ? '—' : mmss(mtta)}</b><small>MTTA for this incident</small></div>
							<div class="pm-card pm-stat rv pend" style="--d:150"><span class="pm-label">Time to resolve</span><b>${mttr == null ? '—' : mmss(mttr)}</b><small>MTTR for this incident</small></div>
							<div class="pm-card pm-stat rv pend" style="--d:300"><span class="pm-label">Customers protected</span><b>${det.impact ? nfmt.format(det.impact.customersAffected) : '—'}</b><small>payments to reconcile, not lose</small></div>`;
						requestAnimationFrame(() => requestAnimationFrame(() => $('pmMttr').querySelectorAll('.pend').forEach((x) => x.classList.remove('pend'))));
					}
				}
			}
		},
		{
			id: 'value', chapter: 'Why it matters', dur: 13000, cam: 'hidden', ambient: 1,
			caption: () => 'From log line to business decision, in one place: parsing, live streaming, incidents, business impact, analysis and reports.',
			html: () => {
				const im = P.data.impact;
				const f = [['parse', 'Universal parser', '12 log formats auto-detected, stack traces folded'], ['live', 'Live stream', 'Server-Sent Events, the moment a log is stored'],
					['bell', 'Incident workflow', 'Alert rules, on-call routing, acknowledge, resolve'], ['rupee', 'Business impact & SLA', 'Money at risk, customers, error budgets'],
					['brain', 'Root-cause analysis', 'NLP pattern mining and a BFSI playbook, PII-safe'], ['doc', 'Executive reports', 'One-click multi-page PDF for leadership']];
				return `<div class="pm-value" style="display:flex;flex-direction:column;align-items:center">
					<div class="pm-kicker rv" style="--d:0">Why it matters</div>
					<div class="pm-h2">${words('From log line to', 100, 60)} <span class="grad">${words('business decision.', 340, 70)}</span></div>
					<div class="pm-feats">${f.map((x, i) => `<div class="pm-card pm-feat rv tilt" style="--d:${600 + i * 110}">${ICON[x[0]]}<b>${x[1]}</b><small>${x[2]}</small></div>`).join('')}</div>
					<div class="pm-band">
						<span class="pm-chip rv" style="--d:1400"><b>${esc(inr(im.valueAtRisk))}</b> at risk surfaced in 24h</span>
						<span class="pm-chip rv" style="--d:1500"><b>${im.incidents}</b> incidents tracked</span>
						${P.data.detail && P.data.detail.resolvedAt ? `<span class="pm-chip rv" style="--d:1600">Today's outage resolved in <b>${mmss((P.data.detail.resolvedAt - P.data.detail.openedAt) / 1000)}</b></span>` : `<span class="pm-chip rv" style="--d:1600">MTTR <b>${LA.business.dur(im.mttrMinutes)}</b></span>`}
					</div>
					<div class="pm-tech rv" style="--d:1800">Java 17 · Spring Boot · H2 · Server-Sent Events · NLP · Chart.js</div>
				</div>`;
			},
			async before() { try { P.data.impact = await LA.api('/api/impact?range=24h'); } catch (e) { /* keep */ } }
		},
		{
			id: 'outro', chapter: 'Thank you', dur: Infinity, cam: 'hidden', ambient: 1,
			caption: () => 'Thank you. Questions?',
			html: () => `<div class="pm-outro" style="display:flex;flex-direction:column;align-items:center">
					<div class="rv pop" style="--d:0">${LOGO}</div>
					<div class="pm-h1">${words('Thank you.', 200, 120)}</div>
					<p class="pm-lead rv" style="--d:700;margin-left:auto;margin-right:auto">Everything you saw ran live: real logs, a real alert, a real incident timeline.</p>
					<div class="pm-url pm-mono rv" style="--d:900">${esc(location.host + location.pathname.replace(/\/$/, ''))}</div>
					<div class="pm-actions rv" style="--d:1100"><button class="pm-cta" data-pm="replay">Replay</button><button class="pm-cta primary" data-pm="exit">Explore the dashboard</button></div>
				</div>`
		}
	];

	function rainLines() {
		let src = P.data.logs || [];
		while (src.length && src.length < 30) src = src.concat(src);
		src = src.slice(0, 45);
		const lines = src.map((r) => {
			const cls = r.level === 'ERROR' || r.level === 'FATAL' ? 'e' : r.level === 'WARN' ? 'wn' : '';
			return `<div class="${cls}"><b>${esc(r.service)}</b>${esc((r.rawLine || r.message || '').split('\n')[0].slice(0, 160))}</div>`;
		}).join('');
		return lines + lines; // twice, for a seamless loop
	}
	async function typeHtml(el, tpl, alive) {
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
	async function loadDetail() {
		if (!P.data.incident) return;
		try { P.data.detail = await LA.api('/api/incidents/' + P.data.incident.id); P.data.incident = P.data.detail; } catch (e) { /* keep */ }
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
			<canvas class="pm-ambient"></canvas><canvas class="pm-world"></canvas>
			<div class="pm-flash"></div>
			<div class="pm-top">
				<div class="pm-brand"><svg viewBox="0 0 84 84"><rect x="1" y="1" width="82" height="82" rx="22" fill="rgba(139,156,255,.14)" stroke="rgba(139,156,255,.5)"/><path d="M14 44h12l6-16 10 30 8-22 4 8h16" fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/></svg>Log Analyzer<span>${esc(BRAND)}</span></div>
				<div class="pm-chapter"><b id="pmNum">01</b><span class="pm-chap" id="pmChap">Welcome</span></div>
				<div class="pm-ctrls">
					<span class="pm-live"><i></i>LIVE</span>
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
			<div class="pm-curtain" id="pmCurtain"><div>Connecting to the live system</div></div>`;
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
			const now = Date.now();
			let rate = 0;
			for (const n of NODES) for (let k = n.events.length - 1; k >= 0 && n.events[k] > now - 60000; k--) rate++;
			setText(P.el.querySelector('[data-live="rate"]'), rate ? nfmt.format(rate) : '…');
			setText(P.el.querySelector('[data-live="upiErr"]'), String(byId[FOCUS].errors.filter((x) => x > now - 60000).length));
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
		const unresolved = P.data.incident && !(P.data.detail && P.data.detail.status === 'RESOLVED');
		if (outage && (scene.id === 'incident' || unresolved)) { W.incident = FOCUS; P.el.classList.add('alarm'); }
		else if (scene.id !== 'resolve' || !unresolved) { W.incident = null; P.el.classList.remove('alarm'); }
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

	async function loadBase() {
		const [s, im, logs] = await Promise.all([
			LA.api('/api/dashboard?range=24h&tz=' + (-new Date().getTimezoneOffset())),
			LA.api('/api/impact?range=24h'),
			LA.api('/api/logs?range=24h&sort=timestamp&dir=desc&page=0&size=80')
		]);
		const errs = s.totals.error + s.totals.fatal;
		Object.assign(P.data, {
			total: s.totals.total, services: s.services.length || 12, errorRate: s.totals.total ? errs * 100 / s.totals.total : 0,
			health: s.healthScore, impact: im, logs: logs.content
		});
	}

	function connect() {
		if (P.es) return;
		P.es = new EventSource('/api/logs/stream');
		P.es.addEventListener('logs', (ev) => { try { onLogs(JSON.parse(ev.data)); } catch (e) { /* ignore */ } });
		P.es.addEventListener('incident', (ev) => {
			try {
				const d = JSON.parse(ev.data);
				if (d.change === 'opened' && d.service === FOCUS && P.data.triggeredAt && !P.data.incident) P.data.incident = d;
			} catch (e) { /* ignore */ }
		});
	}

	async function open(startAt) {
		if (P.open) return;
		if (!P.el) build();
		P.open = true;
		window.LA_PRESENTING = true;
		P.startedAt = Date.now();
		P.last = 0;
		document.body.classList.add('pm-open');
		P.el.classList.add('on');
		$('pmCurtain').classList.remove('gone');
		resize();
		if (innerWidth < 600 && P.captions) toggleCaptions(); // phones: no room for narration
		connect();
		P.raf = requestAnimationFrame(frame);
		try { await loadBase(); } catch (e) {
			Object.assign(P.data, { total: 0, services: 12, errorRate: 0, health: 100, impact: { sla: [], valueAtRisk: 0, incidents: 0, mttrMinutes: null }, logs: [] });
		}
		// prime the map with the last minute of traffic so it is busy from the first frame
		onLogs((P.data.logs || []).slice(0, 40).reverse());
		await sleep(reduce ? 0 : 500);
		$('pmCurtain').classList.add('gone');
		go(startAt || 0);
	}

	function close() {
		if (!P.open) return;
		P.open = false;
		P.token++;
		cancelAnimationFrame(P.raf);
		if (P.es) { P.es.close(); P.es = null; }
		P.el.classList.remove('on', 'alarm');
		document.body.classList.remove('pm-open');
		if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
		setTimeout(() => { if (!P.open) $('pmStage').innerHTML = ''; }, 650);
		if (location.hash === '#present') history.replaceState(null, '', location.pathname + location.search);
		window.LA_PRESENTING = false;
		if (LA.refreshAll) LA.refreshAll();
		document.dispatchEvent(new CustomEvent('la:range')); // refresh the dashboard with what happened
	}

	function replay() {
		P.data.incident = P.data.detail = P.data.analysis = P.data.context = P.data.diagnosedAt = P.data.triggeredAt = null;
		W.incident = null; W.healAt = 0;
		P.startedAt = Date.now();
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
