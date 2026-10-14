package com.example.ordermgt.security;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @CurrentCustomer} from the authenticated session. An anonymous or malformed
 * session fails closed with 401 before the handler runs -- there is no header fallback, because a
 * client-controlled identity header is exactly the hole this resolver exists to close.
 */
@Slf4j
@Component
public class CurrentCustomerResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentCustomer.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        String name = resolveName(webRequest.getNativeRequest(HttpServletRequest.class));
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
        }
        try {
            return Long.valueOf(name);
        } catch (NumberFormatException ex) {
            // A signed-in principal that is not a customer id is a broken identity store, not a
            // client mistake: refuse rather than guess an account.
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED, "the signed-in identity is not a valid customer id");
        }
    }

    private String resolveName(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (isUsable(authentication)) {
            return authentication.getName();
        }
        // Fallback for environments where no security filter populated the holder (e.g. a
        // standalone MockMvc without the chain); still principal-based, never header-based.
        Principal principal = request == null ? null : request.getUserPrincipal();
        if (principal instanceof Authentication fromRequest) {
            return isUsable(fromRequest) ? fromRequest.getName() : null;
        }
        return principal == null ? null : principal.getName();
    }

    private static boolean isUsable(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
