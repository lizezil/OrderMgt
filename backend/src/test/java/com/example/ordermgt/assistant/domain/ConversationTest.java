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

    /** 归档只是可见性（ADR-0013）：一个状态位来回翻，内容一个字都不动。 */
    @Test
    void archivingAndRestoringOnlyFlipTheLifecycle() {
        Conversation conversation = Conversation.start(1001L);
        conversation.titleFrom("what orders do I have?");
        Instant before = conversation.getLastMessageAt();

        conversation.archive();
        assertThat(conversation.isArchived()).isTrue();
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ARCHIVED);

        conversation.restore();
        assertThat(conversation.isArchived()).isFalse();
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);

        // Nothing else changed: the title and timestamps are untouched by visibility.
        assertThat(conversation.getTitle()).isEqualTo("what orders do I have?");
        assertThat(conversation.getLastMessageAt()).isEqualTo(before);
    }

    @Test
    void aFreshConversationStartsActive() {
        Conversation conversation = Conversation.start(1001L);

        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(conversation.isArchived()).isFalse();
    }
}
