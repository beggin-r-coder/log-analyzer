package com.anishka.log_analyzer.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

	@GetMapping("/")
	public String dashboard() {
		return "dashboard";
	}

	@GetMapping("/parser")
	public String parser() {
		return "parser";
	}
}
