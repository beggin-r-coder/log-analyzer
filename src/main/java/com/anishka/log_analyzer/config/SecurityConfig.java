package com.anishka.log_analyzer.config;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Login for the whole app. Credentials come from APP_USERNAME / APP_PASSWORD;
 * if no password is set a random one is generated at startup and printed in
 * the log, so a fresh deployment is never open to the internet.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${app.security.enabled:true}") boolean enabled) throws Exception {
		if (!enabled) {
			log.warn("app.security.enabled=false: the dashboard and APIs are open to anyone who can reach this server.");
			http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable());
			return http.build();
		}
		http.authorizeHttpRequests(a -> a
				.requestMatchers("/login", "/css/**", "/js/**", "/favicon.svg", "/favicon.ico", "/error",
						"/actuator/health", "/actuator/health/**")
				.permitAll()
				// actions that affect everyone or write data are for the admin only;
				// the demo guest can browse, simulate incidents and run AI analysis
				.requestMatchers(HttpMethod.POST, "/api/logs/ingest", "/api/simulator/pause", "/api/simulator/resume")
				.hasRole("ADMIN")
				.requestMatchers("/h2-console/**").hasRole("ADMIN")
				// alert rules are configuration: viewers can read them, only admins change them
				.requestMatchers(HttpMethod.POST, "/api/alert-rules", "/api/alert-rules/**").hasRole("ADMIN")
				.requestMatchers(HttpMethod.PUT, "/api/alert-rules/**").hasRole("ADMIN")
				.requestMatchers(HttpMethod.DELETE, "/api/alert-rules/**").hasRole("ADMIN")
				.anyRequest().authenticated())
				.formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", false).permitAll())
				.logout(l -> l.logoutSuccessUrl("/login?logout"))
				// JS sends the token from the XSRF-TOKEN cookie; the H2 console's own forms are exempt
				.csrf(c -> c.spa().ignoringRequestMatchers("/h2-console/**"))
				.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
				// the H2 console renders in frames from the same origin
				.headers(h -> h.frameOptions(f -> f.sameOrigin()))
				// API calls get a 401 instead of a redirect to the login page
				.exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
						PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
		return http.build();
	}

	@Bean
	UserDetailsService users(@Value("${app.security.username:admin}") String username,
			@Value("${app.security.password:}") String password, @Value("${app.demo.enabled:false}") boolean demo,
			@Value("${app.demo.username:guest}") String guestName, @Value("${app.demo.password:demo}") String guestPassword,
			PasswordEncoder encoder) {
		String pw = password;
		if (pw == null || pw.isBlank()) {
			byte[] b = new byte[15];
			new SecureRandom().nextBytes(b);
			pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
			log.warn("\n\n  No APP_PASSWORD set. Generated login for this run:\n    username: {}\n    password: {}\n"
					+ "  Set APP_USERNAME / APP_PASSWORD to choose your own.\n", username, pw);
		}
		InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(
				User.withUsername(username).password(encoder.encode(pw)).roles("ADMIN").build());
		if (demo) {
			users.createUser(User.withUsername(guestName).password(encoder.encode(guestPassword)).roles("VIEWER").build());
			log.info("Demo mode: read-only guest login enabled ({} / {})", guestName, guestPassword);
		}
		return users;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
