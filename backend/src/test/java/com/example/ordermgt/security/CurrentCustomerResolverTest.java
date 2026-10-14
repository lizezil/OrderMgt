package com.example.ordermgt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * The identity seam: the handler's customer id comes from the session principal, and every way of
 * not having a usable one fails closed with 401. There is deliberately no path that accepts an
 * identity from a request header or any other client-controlled value.
 */
class CurrentCustomerResolverTest {

    private final CurrentCustomerResolver resolver = new CurrentCustomerResolver();

    @AfterEach
    void clearHolder() {
        SecurityContextHolder.clearContext();
    }

    static class Sample {
        public void withCustomer(@CurrentCustomer Long customerId) {}

        public void withoutAnnotation(Long customerId) {}

        public void wrongType(@CurrentCustomer String customerId) {}
    }

    @Test
    void onlyMatchesLongAnnotatedParameters() throws Exception {
        assertThat(resolver.supportsParameter(param("withCustomer", Long.class))).isTrue();
        assertThat(resolver.supportsParameter(param("withoutAnnotation", Long.class))).isFalse();
        assertThat(resolver.supportsParameter(param("wrongType", String.class))).isFalse();
    }

    @Test
    void numericPrincipalBecomesTheCustomerId() throws Exception {
        authenticateAs("1001");
        assertThat(resolve(null)).isEqualTo(1001L);
    }

    @Test
    void noAuthenticationIsUnauthorized() throws Exception {
        assertThatThrownBy(() -> resolve(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            assertThat(((BusinessException) ex).errorCode())
                                    .isEqualTo(ErrorCode.UNAUTHORIZED);
                            assertThat(ex.getMessage())
                                    .isEqualTo(ErrorCode.UNAUTHORIZED.defaultMessage());
                        });
    }

    @Test
    void anonymousSessionIsUnauthorized() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "key",
                                "anonymousUser",
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> resolve(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void principalThatIsNotANumberFailsClosed() throws Exception {
        authenticateAs("abc");
        assertThatThrownBy(() -> resolve(null)).isInstanceOf(BusinessException.class);
    }

    /**
     * Environments without the security filter (standalone MockMvc) fall back to the request's own
     * principal -- still a principal, never a header.
     */
    @Test
    void requestPrincipalIsTheFallbackWhenTheHolderIsEmpty() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Principal principal = new TestingAuthenticationToken("2002", null, List.of());
        when(request.getUserPrincipal()).thenReturn(principal);
        assertThat(resolve(request)).isEqualTo(2002L);
    }

    private static MethodParameter param(String method, Class<?> type) throws Exception {
        return new MethodParameter(Sample.class.getMethod(method, type), 0);
    }

    private static void authenticateAs(String customerId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(customerId, null, List.of()));
    }

    private Object resolve(HttpServletRequest request) throws Exception {
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);
        return resolver.resolveArgument(param("withCustomer", Long.class), null, webRequest, null);
    }
}
