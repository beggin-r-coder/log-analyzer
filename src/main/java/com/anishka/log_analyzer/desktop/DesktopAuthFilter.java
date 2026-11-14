package com.anishka.log_analyzer.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Desktop mode has no login screen, but the local server must still only answer the window the launcher
 * opened. The launcher creates a random token per run and opens {@code /desktop/enter?t=<token>}, which sets a
 * same-site, HTTP-only cookie. Every other request needs that cookie, and the Host header must be the loopback
 * address, so web pages in other tabs (or other programs) cannot use the app or read its data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.desktop.enabled", havingValue = "true")
public class DesktopAuthFilter implements Filter {

	private static final Logger log = LoggerFactory.getLogger(DesktopAuthFilter.class);
	static final String COOKIE = "la_desktop";
	private static final Set<String> HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

	private final String token;

	public DesktopAuthFilter(@Value("${app.desktop.token:}") String token) {
		if (token == null || token.isBlank()) {
			byte[] b = new byte[24];
			new SecureRandom().nextBytes(b);
			token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
			log.warn("No desktop token supplied; generated one for this run: /desktop/enter?t={}", token);
		}
		this.token = token;
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest r = (HttpServletRequest) req;
		HttpServletResponse w = (HttpServletResponse) res;
		if (!HOSTS.contains(host(r.getHeader("Host")))) {
			deny(w, "This app only answers on 127.0.0.1.");
			return;
		}
		String path = r.getRequestURI();
		if ("/desktop/enter".equals(path)) {
			if (matches(r.getParameter("t"))) {
				w.addHeader("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
				String next = r.getParameter("next");
				w.sendRedirect(next != null && next.startsWith("/") && !next.startsWith("//") ? next : "/");
			} else {
				deny(w, "This link has expired. Open Log Analyzer from the Start menu or the tray icon.");
			}
			return;
		}
		if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/favicon") || path.startsWith("/actuator/health")) {
			chain.doFilter(req, res);
			return;
		}
		Cookie[] cookies = r.getCookies();
		if (cookies != null) {
			for (Cookie c : cookies) {
				if (COOKIE.equals(c.getName()) && matches(c.getValue())) {
					chain.doFilter(req, res);
					return;
				}
			}
		}
		deny(w, "Open Log Analyzer from the Start menu or the tray icon.");
	}

	private boolean matches(String candidate) {
		return candidate != null && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
	}

	private static String host(String header) {
		if (header == null) return "";
		String h = header.trim().toLowerCase();
		if (h.startsWith("[")) return h.substring(0, h.indexOf(']') + 1);
		int colon = h.indexOf(':');
		return colon >= 0 ? h.substring(0, colon) : h;
	}

	private static void deny(HttpServletResponse w, String why) throws IOException {
		w.setStatus(HttpServletResponse.SC_FORBIDDEN);
		w.setContentType("text/html;charset=UTF-8");
		w.getWriter().write("<!doctype html><meta charset=utf-8><title>Log Analyzer</title>"
				+ "<body style=\"font-family:system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0;background:#0f1115;color:#e6e7ea\">"
				+ "<div style=\"text-align:center\"><h2 style=\"margin:0 0 8px\">Log Analyzer</h2><p style=\"color:#9aa3b8\">" + why + "</p></div>");
	}
}
