package com.anishka.log_analyzer.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller
public class DashboardController {

	private final String brandName;
	private final String brandTagline;
	private final boolean h2Console;
	private final boolean demoMode;
	private final String guestName;
	private final String guestPassword;
	private final boolean aiEnabled;
	private final boolean desktop;
	private final com.anishka.log_analyzer.source.LogSourceRepository sources;
	private final com.anishka.log_analyzer.repository.LogRecordRepository logs;

	public DashboardController(@Value("${app.brand.name:NovaBank}") String brandName,
			@Value("${app.brand.tagline:BFSI Observability}") String brandTagline,
			@Value("${spring.h2.console.enabled:false}") boolean h2Console,
			@Value("${app.demo.enabled:false}") boolean demoMode, @Value("${app.demo.username:guest}") String guestName,
			@Value("${app.demo.password:demo}") String guestPassword, @Value("${ai.gemini.enabled:false}") boolean aiEnabled,
			@Value("${app.desktop.enabled:false}") boolean desktop, com.anishka.log_analyzer.source.LogSourceRepository sources,
			com.anishka.log_analyzer.repository.LogRecordRepository logs) {
		this.desktop = desktop;
		this.sources = sources;
		this.logs = logs;
		this.aiEnabled = aiEnabled;
		this.demoMode = demoMode;
		this.guestName = guestName;
		this.guestPassword = guestPassword;
		this.h2Console = h2Console;
		this.brandName = brandName;
		this.brandTagline = brandTagline;
	}

	/** Brand shown in the UI and the PDF report; configurable in application.properties. */
	@ModelAttribute
	public void brand(Model model, Authentication auth) {
		boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
		// with security switched off nobody signs in, and everything is allowed
		boolean admin = !signedIn || auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
		model.addAttribute("brandName", brandName);
		model.addAttribute("brandTagline", brandTagline);
		model.addAttribute("username", signedIn ? auth.getName() : null);
		model.addAttribute("isAdmin", admin);
		model.addAttribute("h2Console", h2Console && admin);
		model.addAttribute("demoMode", demoMode);
		model.addAttribute("guestName", guestName);
		model.addAttribute("guestPassword", guestPassword);
		model.addAttribute("aiEnabled", aiEnabled);
		model.addAttribute("desktop", desktop);
	}

	@GetMapping("/")
	public String dashboard() {
		// desktop app, first run: nothing to show yet, so start at "connect your logs"
		if (desktop && sources.count() == 0 && logs.count() == 0) {
			return "redirect:/sources?welcome";
		}
		return "dashboard";
	}

	@GetMapping("/sources")
	public String sources() {
		return "sources";
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
