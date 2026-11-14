package com.anishka.log_analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LogAnalyzerApplication {

	public static void main(String[] args) {
		// LogAnalyzer.exe sets -Dapp.launch=desktop (launcher arguments are dropped when users pass their own)
		if ("desktop".equals(System.getProperty("app.launch")) || java.util.Arrays.asList(args).contains("--desktop")) {
			// LogAnalyzer.exe: desktop application (own window, tray icon, data in the user's profile)
			com.anishka.log_analyzer.desktop.DesktopLauncher.launch(args);
			return;
		}
		SpringApplication.run(LogAnalyzerApplication.class, args);
	}

}
