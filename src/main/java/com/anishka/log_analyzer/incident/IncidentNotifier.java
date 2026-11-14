package com.anishka.log_analyzer.incident;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.anishka.log_analyzer.entity.Incident;

import tools.jackson.databind.json.JsonMapper;

/**
 * Posts incident updates to a chat webhook (Slack or Microsoft Teams incoming
 * webhooks both accept {@code {"text": "..."}}). Disabled unless
 * {@code app.notify.webhook-url} / NOTIFY_WEBHOOK_URL is set.
 */
@Component
public class IncidentNotifier {

	private static final Logger log = LoggerFactory.getLogger(IncidentNotifier.class);

	private final String webhookUrl;
	private final String dashboardUrl;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final JsonMapper json = JsonMapper.builder().build();

	public IncidentNotifier(@Value("${app.notify.webhook-url:}") String webhookUrl,
			@Value("${app.notify.dashboard-url:}") String dashboardUrl) {
		this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
		this.dashboardUrl = dashboardUrl == null ? "" : dashboardUrl.trim();
	}

	public boolean enabled() {
		return !webhookUrl.isEmpty();
	}

	public void opened(Incident i) {
		send(String.format(":rotating_light: [%s] INC-%d %s — owner %s%s", i.getSeverity(), i.getId(), i.getTitle(),
				i.getOwner(), link(i)));
	}

	public void resolved(Incident i) {
		send(String.format(":white_check_mark: INC-%d resolved by %s — %s%s", i.getId(), i.getResolvedBy(), i.getTitle(),
				link(i)));
	}

	private String link(Incident i) {
		return dashboardUrl.isEmpty() ? "" : " " + dashboardUrl + "#incidents";
	}

	private void send(String text) {
		if (!enabled()) {
			return;
		}
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl)).timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text", text)))).build();
			http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
				if (err != null) {
					log.warn("Incident webhook failed: {}", err.toString());
				} else if (res.statusCode() >= 300) {
					log.warn("Incident webhook returned HTTP {}", res.statusCode());
				}
			});
		} catch (RuntimeException e) {
			log.warn("Incident webhook failed: {}", e.toString());
		}
	}
}
