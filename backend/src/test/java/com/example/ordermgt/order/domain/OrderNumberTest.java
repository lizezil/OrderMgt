package com.example.ordermgt.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 订单号是对外的人类可读编号，必须唯一、格式稳定、长度受限于列宽。
 *
 * <p>客户会拿着它来问"我的 ORD… 到哪了"，所以它既不能重复，也不能长到写不进 VARCHAR(32)。
 */
class OrderNumberTest {

    @Test
    void generatedNumbersFollowTheAgreedShape() {
        String value = OrderNumber.generate().value();

        assertThat(value).matches("ORD\\d{14}[0-9A-F]{6}");
        assertThat(value.length()).isLessThanOrEqualTo(32);
    }

    @Test
    void generatedNumbersDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(OrderNumber.generate().value());
        }

        assertThat(seen).hasSize(500);
    }

    @Test
    void blankAndOversizedNumbersAreRejected() {
        assertThatThrownBy(() -> new OrderNumber("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderNumber(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderNumber("ORD" + "9".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
