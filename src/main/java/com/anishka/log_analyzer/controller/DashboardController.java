package com.anishka.log_analyzer.controller;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller
public class DashboardController {

	private final String brandName;
	private final String brandTagline;
	private final boolean h2Console;

	public DashboardController(@Value("${app.brand.name:NovaBank}") String brandName,
			@Value("${app.brand.tagline:BFSI Observability}") String brandTagline,
			@Value("${spring.h2.console.enabled:false}") boolean h2Console) {
		this.h2Console = h2Console;
		this.brandName = brandName;
		this.brandTagline = brandTagline;
	}

	/** Brand shown in the UI and the PDF report; configurable in application.properties. */
	@ModelAttribute
	public void brand(Model model, Principal principal) {
		model.addAttribute("brandName", brandName);
		model.addAttribute("brandTagline", brandTagline);
		model.addAttribute("username", principal == null ? null : principal.getName());
		model.addAttribute("h2Console", h2Console);
	}

	@GetMapping("/")
	public String dashboard() {
		return "dashboard";
	}

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/parser")
	public String parser() {
		return "parser";
	}
}
