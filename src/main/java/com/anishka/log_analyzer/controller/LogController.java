package com.anishka.log_analyzer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.anishka.log_analyzer.model.AnalysisReport;
import com.anishka.log_analyzer.service.LogAnalysisService;

@Controller
public class LogController {

	@Autowired
	private LogAnalysisService service;

	@GetMapping("/")
	public String home() {
		return "index";
	}

	@PostMapping("/analyze")
	public String analyze(@RequestParam("file") MultipartFile file, Model model) {

		AnalysisReport report = service.analyze(file);

		model.addAttribute("report", report);

		model.addAttribute("infoCount", report.getInfoLogs());
		model.addAttribute("warnCount", report.getWarnLogs());
		model.addAttribute("errorCount", report.getErrorLogs());
		model.addAttribute("errorLabels", report.getErrorMap().keySet());
		model.addAttribute("errorValues", report.getErrorMap().values());

		return "enhanced-result";
	}

	@GetMapping("/error-details")
	public String errorDetails(@RequestParam String type, Model model) {

		model.addAttribute("errorType", type);
		model.addAttribute("logs", service.getLogsForError(type));

		return "error-details";
	}
}