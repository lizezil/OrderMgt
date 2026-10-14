package com.example.ordermgt.security;

import com.example.ordermgt.shared.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Session-cookie authentication for the whole API. Every endpoint except the login/csrf pair,
 * health, docs and static assets requires a signed-in session; a missing or expired session answers
 * 401 with the standard response envelope so the frontend can redirect to the login page.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] ANONYMOUS =
            new String[] {
                "/api/auth/login",
                "/api/auth/csrf",
                "/actuator/health",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/",
                "/index.html",
                "/assets/**",
                "/favicon.ico",
                "/error"
            };

    private final ObjectMapper objectMapper;

    /** One repository for the filter chain and for the programmatic login -- they must agree. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            @Value("${security.csrf.enabled:true}") boolean csrfEnabled)
            throws Exception {
        http.csrf(
                        csrf -> {
                            if (csrfEnabled) {
                                // The token lives in a readable cookie so the single-page frontend
                                // can echo it back in the X-XSRF-TOKEN header on every mutation.
                                csrf.csrfTokenRepository(
                                                CookieCsrfTokenRepository.withHttpOnlyFalse())
                                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler());
                            } else {
                                csrf.disable();
                            }
                        })
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .sessionManagement(
                        sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(ANONYMOUS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                new JsonEntryPoint(
                                                        objectMapper,
                                                        HttpStatus.UNAUTHORIZED,
                                                        "UNAUTHORIZED",
                                                        "please sign in to continue"))
                                        .accessDeniedHandler(json403()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private AccessDeniedHandler json403() {
        return (request, response, ex) ->
                writeJson(
                        response,
                        HttpStatus.FORBIDDEN,
                        "FORBIDDEN",
                        "you are not allowed to perform this action");
    }

    private void writeJson(
            HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    /** 401 in the standard envelope: an unauthenticated browser tab is told to sign in. */
    @RequiredArgsConstructor
    private static final class JsonEntryPoint
            implements org.springframework.security.web.AuthenticationEntryPoint {

        private final ObjectMapper objectMapper;
        private final HttpStatus status;
        private final String code;
        private final String message;

        @Override
        public void commence(
                jakarta.servlet.http.HttpServletRequest request,
                HttpServletResponse response,
                org.springframework.security.core.AuthenticationException authException)
                throws IOException {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
        }
    }

    /**
     * SPA-style CSRF handling: the cookie carries the raw token (readable by scripts), the header
     * must carry the raw token, while server-rendered forms would still see the masked value.
     */
    static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

        private final CsrfTokenRequestAttributeHandler plain =
                new CsrfTokenRequestAttributeHandler();
        private final XorCsrfTokenRequestAttributeHandler xor =
                new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
                jakarta.servlet.http.HttpServletRequest request,
                HttpServletResponse response,
                java.util.function.Supplier<CsrfToken> csrfToken) {
            // Deferred: the token (and the cookie) materialise only when something asks for it,
            // e.g. the GET /api/auth/csrf endpoint below.
            this.xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(
                jakarta.servlet.http.HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(headerValue) ? this.plain : this.xor)
                    .resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
