package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Business rule: AI 只能提出白名单里的工具，白名单条目自身的约束必须成立。
 *
 * <p>这是"AI 输出是不可信输入"的第一道闸门：变更工具必须自带一张给客户看的卡片，只读工具不得 假装有卡片；查不到的工具就是不允许执行，回退或反射都不存在。
 */
class ToolWhitelistTest {

    private static final ToolHandler NOOP = (customerId, args) -> ToolResult.of("{}");

    private static ToolCard card() {
        return new ToolCard("Place Order", args -> "Place order", args -> List.of());
    }

    @Test
    void mutatingToolWithoutACardIsRejected() {
        assertThatThrownBy(() -> ToolSpec.mutating("createOrder", "Place order", "{}", null, NOOP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readonlyToolMustNotCarryACard() {
        assertThatThrownBy(() -> new ToolSpec("queryMyOrders", "Query", "{}", false, NOOP, card()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolNameAndSchemaAndHandlerAreMandatory() {
        assertThatThrownBy(() -> ToolSpec.readonly(null, "Query", "{}", NOOP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolSpec.readonly("queryMyOrders", "Query", null, NOOP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolSpec.readonly("queryMyOrders", "Query", "{}", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRegistryOnlyAnswersForRegisteredTools() {
        ToolSpec query = ToolSpec.readonly("queryMyOrders", "Query", "{}", NOOP);
        ToolRegistry registry = new ToolRegistry(List.of(query));

        assertThat(registry.find("queryMyOrders")).contains(query);
        // 模型返回未注册的名字时，查找为空——调用方据此拒绝执行，绝不回退。
        assertThat(registry.find("dropDatabase")).isEmpty();
        assertThat(registry.all()).containsExactly(query);
    }

    @Test
    void aReadonlyToolHasNoSummaryOfItsOwn() {
        ToolSpec query = ToolSpec.readonly("queryMyOrders", "Query", "{}", NOOP);

        assertThat(query.summarise("{}")).isEqualTo("queryMyOrders");
    }

    @Test
    void aToolResultKeepsOnlyAMeaningfulReference() {
        assertThat(ToolResult.of("{}").ref()).isNull();
        assertThat(ToolResult.of("{}", "  ").ref()).isNull();
        assertThat(ToolResult.of("{}", "ORD-1").ref()).isEqualTo("ORD-1");
    }
}
