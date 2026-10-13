package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Business rule: 每客户一条长活状态流（ADR-0014）——一个客户可开到的连接数有上限，事件扇出到它每一条开着的 连接，断开的连接经 emitter
 * 回调自动清理；心跳只往活着的连接发。这条总线不占回合池线程，所以这里也不需要任何线程。
 */
class ConversationEventBusTest {

    private static final Long CUSTOMER = 1001L;

    @Test
    void aCustomerMayHoldUpToTheCapAndNoMore() {
        ConversationEventBus bus = new ConversationEventBus(0, 2);
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        SseEmitter third = mock(SseEmitter.class);

        assertThat(bus.register(CUSTOMER, first)).isTrue();
        assertThat(bus.register(CUSTOMER, second)).isTrue();
        assertThat(bus.register(CUSTOMER, third)).isFalse();
        assertThat(bus.connectionsOf(CUSTOMER)).isEqualTo(2);
    }

    @Test
    void anEventReachesEveryOpenConnectionOfThatCustomerOnly() throws Exception {
        ConversationEventBus bus = new ConversationEventBus(0, 4);
        SseEmitter mine = mock(SseEmitter.class);
        SseEmitter alsoMine = mock(SseEmitter.class);
        SseEmitter someoneElse = mock(SseEmitter.class);
        bus.register(CUSTOMER, mine);
        bus.register(CUSTOMER, alsoMine);
        bus.register(CUSTOMER + 1, someoneElse);

        bus.publish(CUSTOMER, "state", "{\"id\":7}");

        verify(mine).send(any(SseEventBuilder.class));
        verify(alsoMine).send(any(SseEventBuilder.class));
        verify(someoneElse, never()).send(any(SseEventBuilder.class));
    }

    @Test
    void aConnectionThatCompletesIsCleanedUpAndNoLongerReceives() throws Exception {
        ConversationEventBus bus = new ConversationEventBus(0, 4);
        SseEmitter emitter = mock(SseEmitter.class);
        bus.register(CUSTOMER, emitter);
        ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(onCompletion.capture());

        onCompletion.getValue().run();

        assertThat(bus.connectionsOf(CUSTOMER)).isZero();
        bus.publish(CUSTOMER, "state", "{}");
        verify(emitter, never()).send(any(SseEventBuilder.class));
    }

    @Test
    void aConnectionWhoseSendFailsIsDroppedInsteadOfRetried() throws Exception {
        ConversationEventBus bus = new ConversationEventBus(0, 4);
        SseEmitter dead = mock(SseEmitter.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("broken pipe"))
                .when(dead)
                .send(any(SseEventBuilder.class));
        bus.register(CUSTOMER, dead);

        bus.publish(CUSTOMER, "ping", "{}");

        assertThat(bus.connectionsOf(CUSTOMER)).isZero();
    }

    /** 状态流**不占用回合池**：回合池默认只能并发 20 条，而这里 50 条连接照样一条条注册成功——因为一条 连接不持有任何线程。这正是不与回合共用一个池的原因。 */
    @Test
    void manyConnectionsNeedNoTurnThread() {
        ConversationEventBus bus = new ConversationEventBus(0, 200);

        for (int i = 0; i < 50; i++) {
            assertThat(bus.register(CUSTOMER, mock(SseEmitter.class))).isTrue();
        }

        assertThat(bus.connectionsOf(CUSTOMER)).isEqualTo(50);
    }

    @Test
    void publishingToACustomerWithNoConnectionIsHarmless() {
        ConversationEventBus bus = new ConversationEventBus(0, 4);

        bus.publish(CUSTOMER, "state", "{}");

        assertThat(bus.connectionsOf(CUSTOMER)).isZero();
    }
}
