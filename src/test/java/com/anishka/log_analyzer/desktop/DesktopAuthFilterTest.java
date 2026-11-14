package com.anishka.log_analyzer.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

/** The desktop app's local server answers only the window opened with this run's token. */
class DesktopAuthFilterTest {

	private final DesktopAuthFilter filter = new DesktopAuthFilter("tok-123");

	private MockHttpServletResponse run(MockHttpServletRequest r, MockFilterChain chain) throws Exception {
		MockHttpServletResponse w = new MockHttpServletResponse();
		filter.doFilter(r, w, chain);
		return w;
	}

	private static MockHttpServletRequest req(String path, String host) {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
		r.addHeader("Host", host);
		return r;
	}

	@Test
	void tokenLinkSetsAStrictCookieAndRedirects() throws Exception {
		MockHttpServletRequest r = req("/desktop/enter", "127.0.0.1:17171");
		r.setParameter("t", "tok-123");
		r.setParameter("next", "/sources");
		MockHttpServletResponse w = run(r, new MockFilterChain());
		assertEquals(302, w.getStatus());
		assertEquals("/sources", w.getRedirectedUrl());
		String cookie = w.getHeader("Set-Cookie");
		assertTrue(cookie.contains("la_desktop=tok-123") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
	}

	@Test
	void requestsWithoutTheCookieOrFromOtherHostsAreRefused() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		assertEquals(403, run(req("/api/logs", "127.0.0.1:17171"), chain).getStatus());
		assertNull(chain.getRequest());

		MockHttpServletRequest withCookie = req("/api/logs", "evil.example:17171"); // DNS rebinding
		withCookie.setCookies(new Cookie("la_desktop", "tok-123"));
		assertEquals(403, run(withCookie, new MockFilterChain()).getStatus());

		MockHttpServletRequest wrong = req("/desktop/enter", "localhost:17171");
		wrong.setParameter("t", "guess");
		assertEquals(403, run(wrong, new MockFilterChain()).getStatus());

		MockHttpServletRequest open = req("/desktop/enter", "localhost:17171");
		open.setParameter("t", "tok-123");
		open.setParameter("next", "//evil.example");
		assertEquals("/", run(open, new MockFilterChain()).getRedirectedUrl(), "no open redirect");
	}

	@Test
	void theWindowsCookieAndStaticFilesPass() throws Exception {
		MockHttpServletRequest ok = req("/api/logs", "localhost:17171");
		ok.setCookies(new Cookie("la_desktop", "tok-123"));
		MockFilterChain chain = new MockFilterChain();
		run(ok, chain);
		assertNotNull(chain.getRequest());

		MockFilterChain css = new MockFilterChain();
		run(req("/css/dashboard.css", "127.0.0.1:17171"), css);
		assertNotNull(css.getRequest());
	}
}
