package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationTest {

    @Test
    void theFirstCustomerMessageNamesTheConversation() {
        Conversation conversation = Conversation.start(1001L);

        conversation.titleFrom("  what   orders do I have? ");

        assertThat(conversation.getTitle()).isEqualTo("what orders do I have?");
    }

    @Test
    void aLaterMessageDoesNotRenameIt() {
        Conversation conversation = Conversation.start(1001L);
        conversation.titleFrom("first sentence");

        conversation.titleFrom("second sentence");

        assertThat(conversation.getTitle()).isEqualTo("first sentence");
    }

    @Test
    void longTitlesAreTruncated() {
        Conversation conversation = Conversation.start(1001L);

        conversation.titleFrom("a".repeat(100));

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
