package com.example.ordermgt.security;

import com.example.ordermgt.shared.api.ApiResponse;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The session lifecycle: sign in with customerId + PIN, sign out, ask who you are, fetch the CSRF
 * token. The signed-in customer id flows from the session into every handler via
 * {@code @CurrentCustomer} -- no request header carries identity anywhere in the API.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final PinAuthenticationProvider pinAuthenticationProvider;
    private final SecurityContextRepository securityContextRepository;

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        try {
            var authentication =
                    pinAuthenticationProvider.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    request.customerId(), request.pin()));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            // The filter chain reads this same repository, so the session (and every later
            // request) sees the identity that was just established.
            securityContextRepository.saveContext(context, httpRequest, httpResponse);
            return ApiResponse.ok(Map.of("customerId", request.customerId()));
        } catch (AuthenticationException ex) {
            // Unknown customer and wrong PIN share one message: the endpoint must not confirm
            // which customer ids exist.
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "customer id or PIN is incorrect");
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            // Killing the session also kills its security context and its CSRF token binding;
            // the browser's next GET /api/auth/csrf mints a fresh one.
            session.invalidate();
        }
        return ApiResponse.ok(null);
    }

    /** Who the session belongs to; 401 when nobody is signed in. */
    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(@CurrentCustomer Long customerId) {
        return ApiResponse.ok(Map.of("customerId", String.valueOf(customerId)));
    }

    /**
     * Hands the SPA its CSRF token. Touching the {@link CsrfToken} materialises the deferred value,
     * which is what writes the readable XSRF cookie the frontend echoes back as the X-XSRF-TOKEN
     * header. With CSRF disabled (tests) an empty token is returned instead.
     */
    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(HttpServletRequest request) {
        String token = "";
        String headerName = "X-XSRF-TOKEN";
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken csrfToken) {
            token = csrfToken.getToken();
            headerName = csrfToken.getHeaderName();
        }
        return ApiResponse.ok(Map.of("token", token, "headerName", headerName));
    }

    public record LoginRequest(@NotBlank String customerId, @NotBlank String pin) {}
}
