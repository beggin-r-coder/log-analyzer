package com.anishka.log_analyzer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
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

/** Demo mode: the read-only guest can explore but not change shared state. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.demo.enabled=true")
class DemoModeFlowTest {

	@LocalServerPort
	int port;

	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
	private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();

	private String base() {
		return "http://localhost:" + port;
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(base() + path)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private int post(String path, String xsrf) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).POST(HttpRequest.BodyPublishers.ofString("2026-11-08 10:00:00 ERROR test"));
		if (xsrf != null) b.header("X-XSRF-TOKEN", xsrf);
		return http.send(b.header("Content-Type", "text/plain").build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private String xsrf() {
		return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).map(c -> c.getValue()).findFirst().orElse(null);
	}

	@Test
	void guestCanExploreButNotChangeSharedState() throws Exception {
		HttpResponse<String> login = get("/login");
		assertTrue(login.body().contains("Explore as guest"), "login page offers guest access");
		Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(login.body());
		assertTrue(m.find());
		String form = "username=guest&password=demo&_csrf=" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
		HttpResponse<String> signIn = http.send(HttpRequest.newBuilder(URI.create(base() + "/login"))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(base() + "/", signIn.headers().firstValue("Location").orElse(""), "guest login succeeds");

		HttpResponse<String> page = get("/");
		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("read-only"), "menu says the account is read-only");
		assertTrue(page.body().contains("Take the 2-minute tour"), "tour card is rendered");
		assertTrue(!page.body().contains("Open H2 console"), "guests do not see the H2 console link");
		String token = xsrf();
		assertNotNull(token);

		assertEquals(200, get("/api/logs").statusCode(), "guest can read logs");
		assertEquals(200, post("/api/simulator/incident?minutes=1", token), "guest can simulate an incident");
		assertEquals(200, post("/api/logs/parse", token), "guest can preview parsing");
		assertEquals(403, post("/api/simulator/pause", token), "guest cannot pause the stream for everyone");
		assertEquals(403, post("/api/logs/ingest", token), "guest cannot write logs");
		assertEquals(403, get("/h2-console/").statusCode(), "guest cannot open the database console");
	}
}
