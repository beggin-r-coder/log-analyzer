/* Light / dark (neon) theme. Loaded in <head> so the right theme is applied before first paint. */
(function () {
	'use strict';
	var KEY = 'la.theme';
	var saved = null;
	try { saved = localStorage.getItem(KEY); } catch (e) { /* storage blocked */ }
	var initial = saved === 'dark' || saved === 'light' ? saved
		: (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
	document.documentElement.setAttribute('data-theme', initial);

	window.LA_THEME = {
		get: function () { return document.documentElement.getAttribute('data-theme'); },
		set: function (t) {
			document.documentElement.setAttribute('data-theme', t);
			try { localStorage.setItem(KEY, t); } catch (e) { /* storage blocked */ }
			document.dispatchEvent(new CustomEvent('la:theme', { detail: t }));
			document.querySelectorAll('[data-theme-toggle]').forEach(function (b) {
				b.setAttribute('aria-label', t === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
				b.title = b.getAttribute('aria-label');
			});
		},
		toggle: function () { this.set(this.get() === 'dark' ? 'light' : 'dark'); }
	};
	document.addEventListener('click', function (e) {
		if (e.target.closest && e.target.closest('[data-theme-toggle]')) window.LA_THEME.toggle();
	});
	document.addEventListener('DOMContentLoaded', function () { window.LA_THEME.set(window.LA_THEME.get()); });
})();
