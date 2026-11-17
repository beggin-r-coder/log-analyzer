/* Log Analyzer – in-app dialogs (no browser pop-ups) and, in the desktop app, a "not responding" screen. */
(function () {
	'use strict';
	const ICONS = {
		danger: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>',
		power: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v9"/><path d="M6.3 6.3a8 8 0 1 0 11.4 0"/></svg>',
		info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/></svg>',
		trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
		plug: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 2v6M15 2v6M6 8h12v3a6 6 0 0 1-12 0zM12 17v5"/></svg>'
	};
	const esc = (s) => s == null ? '' : String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

	/** Shows a modal dialog; resolves with the id of the button pressed (or null for Esc / outside click). */
	function dialog({ title, message, icon = 'info', tone = 'primary', buttons = [{ id: 'ok', label: 'OK', primary: true }], dismissable = true }) {
		return new Promise((resolve) => {
			const prev = document.activeElement;
			const root = document.createElement('div');
			root.className = 'ui-dialog-root';
			root.innerHTML = `
				<div class="ui-backdrop"></div>
				<div class="ui-dialog tone-${tone}" role="alertdialog" aria-modal="true" aria-labelledby="uiDlgTitle" aria-describedby="uiDlgMsg">
					<div class="ui-dialog-icon">${ICONS[icon] || ICONS.info}</div>
					<div class="ui-dialog-body">
						<h2 id="uiDlgTitle">${esc(title)}</h2>
						<p id="uiDlgMsg">${esc(message)}</p>
					</div>
					<div class="ui-dialog-actions">${buttons.map((b) => `<button type="button" class="ui-btn${b.primary ? ' primary' : ''}${b.danger ? ' danger' : ''}" data-id="${esc(b.id)}">${esc(b.label)}</button>`).join('')}</div>
				</div>`;
			document.body.appendChild(root);
			requestAnimationFrame(() => root.classList.add('open'));
			const done = (id) => {
				root.classList.remove('open');
				document.removeEventListener('keydown', onKey, true);
				setTimeout(() => root.remove(), 180);
				if (prev && prev.focus) prev.focus();
				resolve(id);
			};
			const onKey = (ev) => {
				if (ev.key === 'Escape' && dismissable) { ev.preventDefault(); ev.stopPropagation(); done(null); }
				if (ev.key === 'Tab') { // keep focus inside the dialog
					const f = [...root.querySelectorAll('button')];
					const i = f.indexOf(document.activeElement);
					ev.preventDefault();
					f[(i + (ev.shiftKey ? -1 : 1) + f.length) % f.length].focus();
				}
			};
			document.addEventListener('keydown', onKey, true);
			root.querySelector('.ui-backdrop').addEventListener('click', () => { if (dismissable) done(null); });
			root.querySelectorAll('[data-id]').forEach((b) => b.addEventListener('click', () => done(b.dataset.id)));
			(root.querySelector('.ui-btn.primary') || root.querySelector('.ui-btn')).focus();
		});
	}

	async function confirm({ title, message, confirm: ok = 'OK', cancel = 'Cancel', danger = false, icon }) {
		const id = await dialog({
			title, message, icon: icon || (danger ? 'danger' : 'info'), tone: danger ? 'danger' : 'primary',
			buttons: [{ id: 'cancel', label: cancel }, { id: 'ok', label: ok, primary: !danger, danger }]
		});
		return id === 'ok';
	}

	function alert({ title, message, button = 'OK', icon = 'info', tone = 'primary' }) {
		return dialog({ title, message, icon, tone, buttons: [{ id: 'ok', label: button, primary: true }] });
	}

	window.LA_UI = { dialog, confirm, alert, ICONS };

	// ------------------------------------------------------------------ desktop: server not responding
	if (!(window.APP_USER && window.APP_USER.desktop) && !window.APP_DESKTOP) return;
	let failures = 0, overlay = null, checking = false;
	const realFetch = window.fetch.bind(window);
	window.fetch = async function (input, init) {
		const url = typeof input === 'string' ? input : input.url;
		const local = url.startsWith('/') || url.startsWith(location.origin);
		try {
			const res = await realFetch(input, init);
			if (local) { failures = 0; hide(); }
			return res;
		} catch (e) {
			if (local && !/\/api\/desktop\/quit/.test(url) && ++failures >= 2) show();
			throw e;
		}
	};
	function show() {
		if (overlay || document.body.classList.contains('app-quitting')) return;
		overlay = document.createElement('div');
		overlay.className = 'ui-offline';
		overlay.innerHTML = `<div class="ui-offline-card">
			<div class="ui-dialog-icon tone-danger">${ICONS.plug}</div>
			<h2>Log Analyzer isn't responding</h2>
			<p>The app may have been closed from the tray, or it stopped unexpectedly. Start it again from the Start menu or the desktop shortcut, or try reconnecting.</p>
			<div class="ui-dialog-actions"><button class="ui-btn" data-act="close">Close window</button><button class="ui-btn primary" data-act="retry">Try again</button></div>
			<small class="ui-offline-state" id="uiOfflineState"></small></div>`;
		document.body.appendChild(overlay);
		requestAnimationFrame(() => overlay.classList.add('open'));
		overlay.querySelector('[data-act="close"]').addEventListener('click', () => window.close());
		overlay.querySelector('[data-act="retry"]').addEventListener('click', retry);
		overlay.querySelector('[data-act="retry"]').focus();
	}
	function hide() {
		if (!overlay) return;
		const o = overlay;
		overlay = null;
		o.classList.remove('open');
		setTimeout(() => o.remove(), 200);
	}
	async function retry() {
		if (checking) return;
		checking = true;
		const st = document.getElementById('uiOfflineState');
		if (st) st.textContent = 'Reconnecting…';
		try {
			const r = await realFetch('/actuator/health', { cache: 'no-store' });
			if (r.ok) { failures = 0; hide(); if (window.LA && window.LA.refreshAll) window.LA.refreshAll(); return; }
			throw new Error();
		} catch (e) {
			if (st) st.textContent = 'Still not reachable.';
		} finally { checking = false; }
	}
})();
