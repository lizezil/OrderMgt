package com.example.ordermgt.assistant.infrastructure.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * US-1.10 的验收**只断言文本**：能力边界这一句写在提示词里。行为那半截（模型到底照不照做、不照做时谁
 * 收场）不在这里断言——模型自觉从来不是保证的一部分，服务端兜底有自己的测试（US-1.11）。
 */
class AiPromptsTest {

    @Test
    void theSystemPromptStatesTheCapabilityBoundary() {
        assertThat(AiPrompts.SYSTEM)
                .contains("Your capabilities, in full")
                .contains("refunds, address changes, invoices")
                .contains("cannot do it here");
    }

    /**
     * US-4.6 同样只断言文本那半截：规则把"商品与单价照每次调用注入的清单回答、不要臆造"写进提示词。清单每次都在场 由 grounding
     * 保证（US-6.3）、报出的价落不落账由服务端定价保证（US-1.5），都不在这里断言。
     */
    @Test
    void theSystemPromptBindsItemAndPriceAnswersToTheInjectedList() {
        assertThat(AiPrompts.SYSTEM.replaceAll("\\s+", " "))
                .contains("Item and price questions: answer from the list of available items")
                .contains("Never invent a price or an item that is not in that list");
    }
}
