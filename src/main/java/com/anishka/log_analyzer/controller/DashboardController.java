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

	public DashboardController(@Value("${app.brand.name:NovaBank}") String brandName,
			@Value("${app.brand.tagline:BFSI Observability}") String brandTagline,
			@Value("${spring.h2.console.enabled:false}") boolean h2Console,
			@Value("${app.demo.enabled:false}") boolean demoMode, @Value("${app.demo.username:guest}") String guestName,
			@Value("${app.demo.password:demo}") String guestPassword) {
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
