package com.example.ordermgt.testsupport;

import java.util.List;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test-side stand-ins for the signed-in session.
 *
 * <p>{@link #authenticatedAs(Object)} is for <b>standalone</b> MockMvc only: with no security
 * filter chain there is nothing to load a session, so the identity is set exactly where {@code
 * CurrentCustomerResolver} reads it -- the {@code SecurityContextHolder} of the dispatching thread.
 * Suites that boot the full context must use spring-security-test's {@code user()} instead, because
 * their filter chain reloads the holder from the (empty) session and would wipe this.
 */
public final class TestAuth {

    private TestAuth() {}

    public static RequestPostProcessor authenticatedAs(Object customerId) {
        return request -> {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new TestingAuthenticationToken(String.valueOf(customerId), null, List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    /** An explicitly unauthenticated request: clears any identity a previous test left behind. */
    public static RequestPostProcessor anonymous() {
        return request -> {
            SecurityContextHolder.clearContext();
            return request;
        };
    }
}
