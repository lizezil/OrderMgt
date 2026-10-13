package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 落库的发言必须放得进列宽，且分得清角色。
 *
 * <p>messages.content 是 VARCHAR(8000)；一段超长回复若原样 INSERT 会被数据库拒绝，导致整轮失败，
 * 所以超长内容在进入消息模型时就被截断，而不是让它去撞数据库。
 */
class MessageTest {

    private static final Instant NOW = Instant.now();

    @Test
    void customerAndAssistantTurnsCarryTheirRole() {
        assertThat(Message.customer(1L, "你好", NOW).role()).isEqualTo(Role.CUSTOMER);
        assertThat(Message.assistant(1L, "你好", NOW).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void aCardBearingTurnRemembersItsCard() {
        Message message = Message.assistantWithAction(1L, "创建订单", 9L, NOW);

        assertThat(message.role()).isEqualTo(Role.ASSISTANT);
        assertThat(message.pendingActionId()).isEqualTo(9L);
    }

    @Test
    void anOversizedReplyIsTruncatedInsteadOfBlowingUpTheWrite() {
        Message message = Message.customer(1L, "x".repeat(Message.CONTENT_MAX + 1000), NOW);

        assertThat(message.content().length()).isLessThanOrEqualTo(Message.CONTENT_MAX);
        assertThat(message.content()).endsWith("...[已截断]");
    }

    @Test
    void aReplyAtTheLimitIsKeptVerbatim() {
        String exactly = "x".repeat(Message.CONTENT_MAX);

        assertThat(Message.customer(1L, exactly, NOW).content()).isEqualTo(exactly);
    }

    @Test
    void anEmptyReplyStaysEmpty() {
        assertThat(Message.customer(1L, null, NOW).content()).isNull();
    }
}
