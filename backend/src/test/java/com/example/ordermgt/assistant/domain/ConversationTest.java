package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationTest {

    @Test
    void theFirstCustomerMessageNamesTheConversation() {
        Conversation conversation = Conversation.start(1001L);

        conversation.titleFrom("  我有哪些   订单？ ");

        assertThat(conversation.getTitle()).isEqualTo("我有哪些 订单？");
    }

    @Test
    void aLaterMessageDoesNotRenameIt() {
        Conversation conversation = Conversation.start(1001L);
        conversation.titleFrom("第一句");

        conversation.titleFrom("第二句");

        assertThat(conversation.getTitle()).isEqualTo("第一句");
    }

    @Test
    void longTitlesAreTruncated() {
        Conversation conversation = Conversation.start(1001L);

        conversation.titleFrom("一".repeat(100));

        assertThat(conversation.getTitle()).hasSize(Conversation.TITLE_MAX + 1);
        assertThat(conversation.getTitle()).endsWith("…");
    }

    @Test
    void touchingMovesTheTimestampUsedForSorting() {
        Conversation conversation = Conversation.start(1001L);
        Instant later = Instant.now().plusSeconds(60);

        conversation.touch(later);

        assertThat(conversation.getLastMessageAt()).isEqualTo(later);
        assertThat(conversation.getUpdatedAt()).isEqualTo(later);
    }
}
