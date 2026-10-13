package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PendingActionTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    void aFreshProposalIsPendingAndBoundToItsCustomer() {
        PendingAction action =
                PendingAction.propose(1001L, 7L, "createOrder", "{}", "Place order", TTL);

        assertThat(action.getStatus()).isEqualTo(PendingActionStatus.PENDING);
        assertThat(action.isPending()).isTrue();
        assertThat(action.isOwnedBy(1001L)).isTrue();
        assertThat(action.isOwnedBy(2002L)).isFalse();
        assertThat(action.getToken()).hasSize(48);
        assertThat(action.isExpiredAt(Instant.now())).isFalse();
    }

    @Test
    void resolvingIsOneWay() {
        PendingAction action =
                PendingAction.propose(1001L, 7L, "createOrder", "{}", "Place order", TTL);

        action.resolve(PendingActionStatus.CONFIRMED, "ORD-1", Instant.now());

        assertThat(action.getStatus()).isEqualTo(PendingActionStatus.CONFIRMED);
        assertThat(action.getResultRef()).isEqualTo("ORD-1");
        // A second confirmation must not be able to execute the action twice.
        assertThatThrownBy(
                        () -> action.resolve(PendingActionStatus.CONFIRMED, "ORD-2", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTerminalStatusesMayResolveAnAction() {
        PendingAction action =
                PendingAction.propose(1001L, 7L, "createOrder", "{}", "Place order", TTL);

        assertThatThrownBy(() -> action.resolve(PendingActionStatus.PENDING, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedToolArgumentsAreRejectedRatherThanTruncated() {
        String tooLong = "{\"lines\":\"" + "x".repeat(PendingAction.ARGUMENTS_MAX) + "\"}";

        assertThatThrownBy(() -> PendingAction.propose(1001L, 7L, "createOrder", tooLong, "s", TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryIsDecidedByTheDeadline() {
        PendingAction action =
                PendingAction.propose(1001L, 7L, "createOrder", "{}", "Place order", Duration.ZERO);

        assertThat(action.isExpiredAt(Instant.now())).isTrue();
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            tokens.add(
                    PendingAction.propose(1001L, 7L, "createOrder", "{}", "Place order", TTL)
                            .getToken());
        }
        assertThat(tokens).hasSize(200);
    }

    @Test
    void aProposalNeedsACustomerAndAConversation() {
        assertThatThrownBy(() -> PendingAction.propose(null, 7L, "createOrder", "{}", "s", TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingAction.propose(1001L, null, "createOrder", "{}", "s", TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
