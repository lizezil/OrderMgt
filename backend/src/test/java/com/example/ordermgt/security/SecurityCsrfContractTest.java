package com.example.ordermgt.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CSRF with the chain switched back on (the ordinary suite runs with it off). The contract the
 * frontend depends on: a mutation without the token is 403, GET /api/auth/csrf plants the readable
 * XSRF-TOKEN cookie, and echoing that cookie value back in the X-XSRF-TOKEN header -- the exact
 * browser flow -- passes.
 */
@SpringBootTest(properties = "security.csrf.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityCsrfContractTest {

    private static final String LOGIN = "{\"customerId\":\"1001\",\"pin\":\"1111\"}";

    @Autowired private MockMvc mvc;

    @Test
    void mutationWithoutCsrfTokenIs403() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void csrfEndpointPlantsAReadableCookieTheHeaderEchoPasses() throws Exception {
        MvcResult csrf =
                mvc.perform(get("/api/auth/csrf"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("OK"))
                        .andReturn();
        Cookie xsrf = csrf.getResponse().getCookie("XSRF-TOKEN");
        // With CSRF on, the endpoint must materialise the cookie (it is deferred until asked).
        org.assertj.core.api.Assertions.assertThat(xsrf).isNotNull();
        org.assertj.core.api.Assertions.assertThat(xsrf.getValue()).isNotBlank();

        mvc.perform(
                        post("/api/auth/login")
                                .cookie(xsrf)
                                .header("X-XSRF-TOKEN", xsrf.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }
}
