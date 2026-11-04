package com.anishka.log_analyzer.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller
public class DashboardController {

	private final String brandName;
	private final String brandTagline;

	public DashboardController(@Value("${app.brand.name:NovaBank}") String brandName,
			@Value("${app.brand.tagline:BFSI Observability}") String brandTagline) {
		this.brandName = brandName;
		this.brandTagline = brandTagline;
	}

	/** Brand shown in the UI and the PDF report; configurable in application.properties. */
	@ModelAttribute
	public void brand(Model model) {
		model.addAttribute("brandName", brandName);
		model.addAttribute("brandTagline", brandTagline);
	}

	@GetMapping("/")
	public String dashboard() {
		return "dashboard";
	}

	@GetMapping("/parser")
	public String parser() {
		return "parser";
	}
}
