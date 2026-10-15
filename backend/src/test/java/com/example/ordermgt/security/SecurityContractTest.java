package com.example.ordermgt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The session contract through the real filter chain: anonymous is 401 in the standard envelope, a
 * correct PIN starts a session, that session -- and only that session -- is the identity (a spoofed
 * X-Customer-Id header changes nothing), logout ends it, and the anonymous whitelist holds exactly
 * login/csrf/health.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityContractTest {

    @Autowired private MockMvc mvc;

    private MockHttpSession login(String customerId, String pin) throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"customerId\":\""
                                                        + customerId
                                                        + "\",\"pin\":\""
                                                        + pin
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("OK"))
                        .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void anonymousApiCallIs401InTheStandardEnvelope() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void correctPinStartsASessionThatIdentifiesTheCustomer() throws Exception {
        MockHttpSession session = login("1001", "1111");

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value("1001"));
    }

    @Test
    void wrongPinIs401AndStartsNoSession() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\":\"1001\",\"pin\":\"0000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("customer id or PIN is incorrect"));
    }

    @Test
    void aSpoofedCustomerHeaderCannotChangeTheIdentity() throws Exception {
        MockHttpSession session = login("1001", "1111");

        mvc.perform(get("/api/auth/me").session(session).header("X-Customer-Id", "2002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value("1001"));
    }

    @Test
    void someoneElsesSessionIsTheirIdentityNotYours() throws Exception {
        MockHttpSession victim = login("1001", "1111");

        // The attacker's own (empty) session with the victim's id in a header gains nothing.
        mvc.perform(get("/api/auth/me").header("X-Customer-Id", "1001"))
                .andExpect(status().isUnauthorized());
        assertThat(victim.getId()).isNotNull();
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = login("1001", "1111");

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    /** 登录就把起点写进会话，绝对上限才有得可算。 */
    @Test
    void loginStampsTheSessionStart() throws Exception {
        MockHttpSession session = login("1001", "1111");

        assertThat(session.getAttribute(SessionLifetimeFilter.STARTED_AT))
                .isInstanceOf(Instant.class);
    }

    /** 会话过了绝对上限就是陌生人：容器只看空闲，不看总时长，所以一个一直在用、但已经跨过 12 小时的会话 必须在这里被掐掉——身份还在上下文里，但下一次请求就是 401。 */
    @Test
    void aSessionPastItsAbsoluteLifetimeIs401EvenWhileActive() throws Exception {
        MockHttpSession session = login("1001", "1111");
        // 假装这段会话是 13 小时前开的：重新登录过、每个请求都没超过空闲上限，但总时长已经到点。
        session.setAttribute(
                SessionLifetimeFilter.STARTED_AT, Instant.now().minus(Duration.ofHours(13)));

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void theAnonymousWhitelistIsExactlyLoginCsrfAndHealth() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        // Everything else -- even auth-adjacent endpoints -- needs the session.
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginHandsOutACustomerScopedSessionNotAUserId() throws Exception {
        MockHttpSession first = login("1001", "1111");
        MockHttpSession second = login("2002", "2222");

        mvc.perform(get("/api/auth/me").session(first))
                .andExpect(jsonPath("$.data.customerId").value("1001"));
        mvc.perform(get("/api/auth/me").session(second))
                .andExpect(jsonPath("$.data.customerId").value("2002"));
    }
}
