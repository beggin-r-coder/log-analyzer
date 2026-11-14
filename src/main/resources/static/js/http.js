/* Adds the CSRF token to state-changing requests and sends the user to the
 * login page when their session has expired. Loaded before the other scripts. */
(function () {
	'use strict';
	const original = window.fetch.bind(window);
	const token = () => {
		const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
		return m ? decodeURIComponent(m[1]) : null;
	};
	window.fetch = async function (input, init) {
		init = Object.assign({}, init);
		const method = (init.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
		const url = new URL(typeof input === 'string' ? input : input.url, location.href);
		if (url.origin === location.origin && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
			const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
			const t = token();
			if (t && !headers.has('X-XSRF-TOKEN')) headers.set('X-XSRF-TOKEN', t);
			init.headers = headers;
		}
		init.credentials = init.credentials || 'same-origin';
		const res = await original(input, init);
		if (res.status === 401 && url.origin === location.origin && url.pathname.startsWith('/api/')) {
			location.href = '/login?expired';
		}
		return res;
	};
})();
