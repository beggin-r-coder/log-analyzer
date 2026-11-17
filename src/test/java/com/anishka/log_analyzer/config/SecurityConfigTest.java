package com.anishka.log_analyzer.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;


@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

	@Autowired
	MockMvc mvc;

	@Test
	void pagesRequireLoginAndApisAnswer401() throws Exception {
		// browsers (Accept: text/html) are sent to the login page
		mvc.perform(get("/").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/parser").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
		mvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/logs/stream")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/simulator/pause").with(csrf())).andExpect(status().isUnauthorized());
	}

	@Test
	void loginPageHealthCheckAndStaticFilesArePublic() throws Exception {
		mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("Sign in")));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("UP")));
		mvc.perform(get("/css/dashboard.css")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", Matchers.containsString("max-age")));
	}

	@Test
	void formLoginAcceptsTheConfiguredPasswordOnly() throws Exception {
		mvc.perform(formLogin("/login").user("admin").password("test-password")).andExpect(authenticated())
				.andExpect(redirectedUrl("/"));
		mvc.perform(formLogin("/login").user("admin").password("wrong")).andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"));
		// the guest account only exists in demo mode
		mvc.perform(formLogin("/login").user("guest").password("demo")).andExpect(unauthenticated());
	}

	@Test
	void signedInUsersCanReadButWritesNeedTheCsrfToken() throws Exception {
		mvc.perform(get("/api/logs").with(user("admin"))).andExpect(status().isOk());
		mvc.perform(get("/").with(user("admin"))).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("Sign out")));
		mvc.perform(post("/api/simulator/pause").with(user("admin"))).andExpect(status().isForbidden());

		// the full cookie + header round trip is covered against a real server in BrowserSessionFlowTest
	}
}
