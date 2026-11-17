package com.anishka.log_analyzer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Signs in and calls the API exactly like a browser running static/js/http.js would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrowserSessionFlowTest {

	@LocalServerPort
	int port;

	@Test
	void loginThenWriteWithTheXsrfCookieEchoedInTheHeader() throws Exception {
		CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
		String base = "http://localhost:" + port;

		HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/login")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, login.statusCode());
		Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(login.body());
		assertTrue(m.find(), "login form carries a CSRF field");

		String form = "username=admin&password=test-password&_csrf=" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
		HttpResponse<String> signIn = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, signIn.statusCode());
		assertEquals(base + "/", signIn.headers().firstValue("Location").orElse(""));

		HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, page.statusCode());
		HttpCookie xsrf = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst().orElse(null);
		assertNotNull(xsrf, "XSRF-TOKEN cookie must be available to JavaScript");

		HttpRequest.Builder pause = HttpRequest.newBuilder(URI.create(base + "/api/simulator/pause")).POST(HttpRequest.BodyPublishers.noBody());
		assertEquals(403, http.send(pause.build(), HttpResponse.BodyHandlers.discarding()).statusCode(), "no token -> rejected");
		assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/simulator/pause")).header("X-XSRF-TOKEN", "forged")
				.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), "wrong token -> rejected");
		assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/simulator/pause")).header("X-XSRF-TOKEN", xsrf.getValue())
				.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), "cookie echoed -> accepted");
		http.send(HttpRequest.newBuilder(URI.create(base + "/api/simulator/resume")).header("X-XSRF-TOKEN", xsrf.getValue())
				.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());

		HttpResponse<String> logout = http.send(HttpRequest.newBuilder(URI.create(base + "/logout")).header("X-XSRF-TOKEN", xsrf.getValue())
				.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(302, logout.statusCode());
		assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/logs")).build(), HttpResponse.BodyHandlers.discarding()).statusCode(),
				"signed out -> API locked again");
	}
}
