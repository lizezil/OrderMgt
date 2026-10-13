package com.example.ordermgt.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 金额的表示与运算。
 *
 * <p>金额是客户最终看到、也是账要对的数字，所以它的规则必须不依赖任何框架就能验证：保留两位小数、 不能出现负数、单价乘数量与多行合计的结果必须可预期。
 */
class MoneyTest {

    @Test
    void moneyAlwaysKeepsTwoDecimalsWithRounding() {
        assertThat(Money.of(new BigDecimal("5.005")).amount()).isEqualByComparingTo("5.01");
        assertThat(Money.of(new BigDecimal("19.9")).amount()).isEqualByComparingTo("19.90");
        assertThat(Money.of(new BigDecimal("0.001")).amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void moneyCannotBeNegative() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyCannotBeNull() {
        assertThatThrownBy(() -> Money.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void unitPriceTimesQuantityGivesLineAmount() {
        Money lineAmount = Money.of(new BigDecimal("19.90")).multiply(3);

        assertThat(lineAmount.amount()).isEqualByComparingTo("59.70");
    }

    @Test
    void zeroQuantityGivesZeroLineAmount() {
        assertThat(Money.of(new BigDecimal("19.90")).multiply(0).amount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void summingLineAmountsGivesOrderTotal() {
        Money total =
                Money.of(new BigDecimal("19.90"))
                        .multiply(3)
                        .plus(Money.of(new BigDecimal("5.00")).multiply(2));

        assertThat(total.amount()).isEqualByComparingTo("69.70");
        assertThat(Money.ZERO.plus(Money.of(new BigDecimal("9.99"))).amount())
                .isEqualByComparingTo("9.99");
    }
}
