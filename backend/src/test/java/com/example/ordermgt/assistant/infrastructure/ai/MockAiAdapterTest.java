package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 离线桩要听懂 README 教用户敲的那几句中文。
 *
 * <p>桩的固定台词是中文（{@code OfflinePrompts}），演示时敲的也是中文句子；关键词表如果只认英文，文档里的演示
 * 提示词就会落到"我没听懂"那一句上，闭环当场断掉。这里钉住三种意图各自的中文触发，以及能力之外的请求 得到的那句如实拒绝（US-1.10）——它不能落进任何一个关键词支里被答非所问。
 */
class MockAiAdapterTest {

    private final MockAiAdapter adapter = new MockAiAdapter();

    @Test
    void aChineseLookupProposesTheOrderList() {
        AssistantTurn turn = respond("我有哪些订单？");

        assertThat(turn.toolCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("queryMyOrders"));
    }

    @Test
    void aChineseOrderProposesCreateOrderWithOnlySkuAndQuantity() {
        AssistantTurn turn = respond("帮我下单 2 个 GADGET-02");

        assertThat(turn.toolCalls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.name()).isEqualTo("createOrder");
                            assertThat(call.argumentsJson()).contains("GADGET-02").contains("2");
                        });
    }

    @Test
    void aChineseCancelProposesTheCancelOfTheNamedOrder() {
        AssistantTurn turn = respond("取消订单 ORD2026101421214197C492");

        assertThat(turn.toolCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("cancelOrder"));
    }

    @Test
    void anUnrecognisedChineseSentenceSaysSo() {
        AssistantTurn turn = respond("今天天气怎么样");

        assertThat(turn.toolCalls()).isEmpty();
    }

    /**
     * US-1.10：能力之外的请求（退款 / 开发票 / 改地址）要得到一句如实的"做不了"，不提议任何调用—— 「帮我退款订单 ORD…」里带着「订单」二字，若落进列表支就会被答非所问。
     */
    @Test
    void aChineseAskOutsideTheCapabilityGetsAPlainRefusal() {
        for (String ask : List.of("帮我退款", "帮我开发票", "帮我改收货地址")) {
            AssistantTurn turn = respond(ask);

            assertThat(turn.toolCalls()).as(ask).isEmpty();
            assertThat(turn.text()).as(ask).contains("做不了");
        }
    }

    private AssistantTurn respond(String message) {
        return adapter.respond(1001L, 7L, List.of(new ChatMessage(Role.CUSTOMER, message)));
    }
}
