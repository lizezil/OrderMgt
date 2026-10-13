package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 模型不得臆造 sku 与单价——服务端把真实目录的事实注入提示词。
 *
 * <p>这是 ADR-0006 那次真实幻觉（模型把商品名当 sku）的防线：facts() 必须把每个在售 sku 连同它的单价 摆出来，模型照抄即可。这里断言的是 facts()
 * 的输出这一公开契约——它含哪些事实，是模型能否"说对"的根据。
 */
class CatalogGroundingProviderTest {

    @Test
    void factsCarryEveryActiveSkuWithItsUnitPrice() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findAllActive())
                .thenReturn(
                        List.of(
                                new CatalogItem(
                                        1L, "WIDGET-01", "Widget", new BigDecimal("19.90"), true),
                                new CatalogItem(
                                        2L, "GIZMO-03", "Gizmo", new BigDecimal("4.50"), true)));

        String facts = new CatalogGroundingProvider(catalog).facts();

        // 每个在售商品都要把 sku 与单价摆到模型面前，它才能照抄而不是编一个。
        assertThat(facts).contains("WIDGET-01").contains("19.90");
        assertThat(facts).contains("GIZMO-03").contains("4.50");
    }

    @Test
    void factsWithNothingActiveInventNoItems() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findAllActive()).thenReturn(List.of());

        // 目录为空时宁可什么都不说，也不能凭空列出一个商品。
        assertThat(new CatalogGroundingProvider(catalog).facts()).doesNotContain("unit price");
    }
}
