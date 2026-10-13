package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationStateTest {

    @Test
    void aRunningRunWinsOverEverything() {
        assertThat(ConversationState.derive(true, true)).isEqualTo(ConversationState.RUNNING);
        assertThat(ConversationState.derive(true, false)).isEqualTo(ConversationState.RUNNING);
    }

    @Test
    void aliveCardWithNoRunMeansWaitingApproval() {
        assertThat(ConversationState.derive(false, true))
                .isEqualTo(ConversationState.WAITING_APPROVAL);
    }

    @Test
    void neitherMeansIdle() {
        assertThat(ConversationState.derive(false, false)).isEqualTo(ConversationState.IDLE);
    }
}
