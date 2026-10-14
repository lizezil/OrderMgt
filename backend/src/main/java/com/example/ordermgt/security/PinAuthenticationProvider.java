package com.example.ordermgt.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Demo-grade identity: customerId -&gt; PIN pairs declared in configuration ({@code
 * assistant.auth.pins}, e.g. {@code "1001:1234,2002:5678"}).
 *
 * <p>There is no user table on purpose -- the demo's customer is a business key, not an account.
 * Rotating a PIN means redeploying with a new value; unknown customer and wrong PIN share one
 * failure message so the endpoint cannot be used to enumerate customers.
 */
@Slf4j
@Component
public class PinAuthenticationProvider implements AuthenticationProvider {

    private final Map<Long, String> pinsByCustomer;

    public PinAuthenticationProvider(@Value("${assistant.auth.pins:}") String pins) {
        this.pinsByCustomer = parse(pins);
        log.info("PIN auth configured for {} customer(s)", pinsByCustomer.size());
    }

    /** Parses {@code "customerId:pin,customerId:pin"}; fails fast on a malformed spec. */
    static Map<Long, String> parse(String spec) {
        Map<Long, String> result = new HashMap<>();
        if (spec == null || spec.isBlank()) {
            return Map.of();
        }
        for (String entry : spec.split(",")) {
            String pair = entry.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int colon = pair.indexOf(':');
            if (colon <= 0 || colon == pair.length() - 1) {
                throw new IllegalStateException(
                        "assistant.auth.pins entry must look like 'customerId:pin': '"
                                + pair
                                + "'");
            }
            long customerId = Long.parseLong(pair.substring(0, colon).trim());
            String pin = pair.substring(colon + 1).trim();
            if (result.put(customerId, pin) != null) {
                throw new IllegalStateException(
                        "assistant.auth.pins lists customer " + customerId + " twice");
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        String customerId = authentication.getName();
        String pin =
                authentication.getCredentials() == null
                        ? ""
                        : String.valueOf(authentication.getCredentials());
        long id;
        try {
            id = Long.parseLong(customerId);
        } catch (NumberFormatException ex) {
            // Not a numeric customer id can never match a configured pair.
            throw new UsernameNotFoundException("no such customer");
        }
        if (!pin.equals(pinsByCustomer.get(id))) {
            throw new UsernameNotFoundException("no such customer");
        }
        return new UsernamePasswordAuthenticationToken(
                customerId, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
