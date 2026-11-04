package com.anishka.log_analyzer.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.anishka.log_analyzer.model.AnalysisReport;
import com.anishka.log_analyzer.model.CriticalIncident;

@Service
public class LogAnalysisService {

	private Map<String, List<String>> lastErrorLogs = new HashMap<>();

	private static final Pattern LOG_PATTERN = Pattern
			.compile("^(\\S+)\\s+\\[(\\w+)\\]\\s+\\[(.*?)\\]\\s+\\[(.*?)\\]\\s+\\[(.*?)\\]\\s+(.*)$");

	public AnalysisReport analyze(MultipartFile file) {

		int total = 0;
		int info = 0;
		int warn = 0;
		int error = 0;

		Map<String, Integer> errorMap = new HashMap<>();
		Map<String, List<String>> errorLogsMap = new HashMap<>();
		Map<String, Integer> hourlyErrors = new LinkedHashMap<>();

		try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {

			String line;

			while ((line = br.readLine()) != null) {

				total++;

				Matcher matcher = LOG_PATTERN.matcher(line);

				if (!matcher.matches()) {
					continue;
				}

				String timestamp = matcher.group(1);

				String level = matcher.group(2);

				String service = matcher.group(3);

				String requestId = matcher.group(4);

				String ip = matcher.group(5);

				String message = matcher.group(6);

				switch (level) {

				case "INFO":
					info++;
					break;

				case "WARN":
					warn++;
					break;

				case "ERROR":

					error++;

					String category = classifyError(message);

					errorMap.put(category, errorMap.getOrDefault(category, 0) + 1);

					errorLogsMap.computeIfAbsent(category, k -> new ArrayList<>()).add(line);

					String hour = timestamp.substring(11, 13);

					hourlyErrors.put(hour, hourlyErrors.getOrDefault(hour, 0) + 1);

					break;
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		Map<String, Integer> sortedMap = errorMap.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

		lastErrorLogs = errorLogsMap;

		List<CriticalIncident> incidents = new ArrayList<>();

		sortedMap.entrySet().stream().limit(5).forEach(entry -> {

			String severity;

			if (entry.getValue() > 100) {
				severity = "HIGH";
			} else if (entry.getValue() > 50) {
				severity = "MEDIUM";
			} else {
				severity = "LOW";
			}

			incidents.add(new CriticalIncident(entry.getKey(), entry.getValue(), severity));
		});

		double errorPercentage = total == 0 ? 0 : ((warn + error) * 100.0) / total;

		double healthScore = Math.max(0, 100 - errorPercentage);

		AnalysisReport report = new AnalysisReport(total, info, warn, error, sortedMap);

		report.setHealthScore(healthScore);
		report.setCriticalIncidents(incidents);
		report.setHourlyErrors(hourlyErrors);

		return report;
	}

	public List<String> getLogsForError(String errorType) {
		return lastErrorLogs.getOrDefault(errorType, new ArrayList<>());
	}

	private String classifyError(String message) {

		String msg = message.toLowerCase();

		if (msg.contains("database")) {
			return "Database Issues";
		}

		if (msg.contains("authentication")) {
			return "Authentication Issues";
		}

		if (msg.contains("message queue")) {
			return "Messaging Issues";
		}

		if (msg.contains("gateway")) {
			return "API Gateway Issues";
		}

		if (msg.contains("null pointer")) {
			return "Application Exceptions";
		}

		if (msg.contains("rollback")) {
			return "Transaction Failures";
		}

		return "Other Errors";
	}
}