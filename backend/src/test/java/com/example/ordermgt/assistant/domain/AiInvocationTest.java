package com.example.ordermgt.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Business rule: 审计行必须永远写得进去——model 列 NOT NULL、error 列 VARCHAR(1000)，两个约束都在 {@link
 * AiInvocation#record} 里解决，调用方不该各自防一遍。
 */
class AiInvocationTest {

    @Test
    void aTurnThatNeverNamedAModelStillProducesAInsertableRow() {
        assertThat(
                        AiInvocation.record(
                                        1L,
                                        1001L,
                                        null,
                                        null,
                                        null,
                                        null,
                                        AiInvocationOutcome.ERROR,
                                        "boom",
                                        null)
                                .model())
                .isEqualTo("unknown");
        assertThat(
                        AiInvocation.record(
                                        1L,
                                        1001L,
                                        "  ",
                                        null,
                                        null,
                                        null,
                                        AiInvocationOutcome.ERROR,
                                        "boom",
                                        null)
                                .model())
                .isEqualTo("unknown");
    }

    @Test
    void anOversizedProviderErrorIsTruncatedInsteadOfFailingTheInsert() {
        AiInvocation invocation =
                AiInvocation.record(
                        1L,
                        1001L,
                        "qwen-max",
                        1,
                        1,
                        42,
                        AiInvocationOutcome.ERROR,
                        "x".repeat(5000),
                        null);

        assertThat(invocation.error()).hasSize(1000);
    }

    @Test
    void aNamedModelAndACleanErrorPassThroughUntouched() {
        AiInvocation invocation =
                AiInvocation.record(
                        1L, 1001L, "qwen-max", 11, 22, 4242, AiInvocationOutcome.OK, null, "[]");

        assertThat(invocation.model()).isEqualTo("qwen-max");
        assertThat(invocation.error()).isNull();
        assertThat(invocation.outcome()).isEqualTo(AiInvocationOutcome.OK);
        assertThat(invocation.createdAt()).isNotNull();
    }
}
