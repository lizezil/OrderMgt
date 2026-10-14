package com.example.ordermgt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The demo identity store: parse once at startup, check one pair per login, fail identically for
 * unknown customer and wrong PIN so the endpoint cannot enumerate customers.
 */
class PinAuthenticationProviderTest {

    private final PinAuthenticationProvider provider =
            new PinAuthenticationProvider("1001:1111,2002:2222");

    @Test
    void correctPinAuthenticatesAsThatCustomer() {
        Authentication result = provider.authenticate(token("1001", "1111"));
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo("1001");
        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void wrongPinIsRejected() {
        assertThatThrownBy(() -> provider.authenticate(token("1001", "9999")))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void unknownCustomerIsRejectedTheSameWay() {
        Throwable wrongPin = catchThrowable(() -> provider.authenticate(token("1001", "9999")));
        Throwable unknown = catchThrowable(() -> provider.authenticate(token("3003", "1111")));
        assertThat(wrongPin).isInstanceOf(AuthenticationException.class);
        assertThat(unknown).isInstanceOf(AuthenticationException.class);
        // One message for both cases: no customer-id enumeration through the login form.
        assertThat(unknown.getMessage()).isEqualTo(wrongPin.getMessage());
    }

    @Test
    void nonNumericCustomerIdIsRejected() {
        assertThatThrownBy(() -> provider.authenticate(token("abc", "1111")))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void supportsPasswordTokens() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }

    @Test
    void parseAcceptsPairsAndIgnoresBlankEntries() {
        assertThat(PinAuthenticationProvider.parse(" 1001:1111 ,, 2002:2222 "))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1001L, "1111", 2002L, "2222"));
        assertThat(PinAuthenticationProvider.parse("")).isEmpty();
        assertThat(PinAuthenticationProvider.parse(null)).isEmpty();
    }

    @Test
    void parseFailsFastOnMalformedSpecs() {
        assertThatThrownBy(() -> PinAuthenticationProvider.parse("1001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PinAuthenticationProvider.parse("1001:"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PinAuthenticationProvider.parse(":1111"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PinAuthenticationProvider.parse("1001:1111,1001:2222"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PinAuthenticationProvider.parse("notanumber:1111"))
                .isInstanceOf(NumberFormatException.class);
    }

    private static UsernamePasswordAuthenticationToken token(String id, String pin) {
        return new UsernamePasswordAuthenticationToken(id, pin);
    }
}
