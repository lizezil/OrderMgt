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

        String facts = provider(catalog).facts();

        // 每个在售商品都要把 sku 与单价摆到模型面前，它才能照抄而不是编一个。
        assertThat(facts).contains("WIDGET-01").contains("19.90");
        assertThat(facts).contains("GIZMO-03").contains("4.50");
    }

    @Test
    void factsWithNothingActiveInventNoItems() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findAllActive()).thenReturn(List.of());

        // 目录为空时宁可什么都不说，也不能凭空列出一个商品。
        assertThat(provider(catalog).facts()).doesNotContain("unit price");
    }

    /**
     * 上限是上下文窗口的生存问题：目录涨到几千条时，无上限的 facts() 会把每次调用都变成一次 context-length
     * 400——助手不是降级，是整体报废。行数与字符双保险，超出即截断并声明是部分视图。
     */
    @Test
    void factsAreCappedSoAVastCatalogCannotBlowTheContextWindow() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        List<CatalogItem> many =
                java.util.stream.IntStream.rangeClosed(1, 500)
                        .mapToObj(
                                i ->
                                        new CatalogItem(
                                                (long) i,
                                                "SKU-%04d".formatted(i),
                                                "Item " + i,
                                                new java.math.BigDecimal("1.00"),
                                                true))
                        .toList();
        when(catalog.findAllActive()).thenReturn(many);

        String facts = provider(catalog).facts();

        assertThat(facts).contains("SKU-0001");
        assertThat(facts).doesNotContain("SKU-0201");
        assertThat(facts).contains("partial");
        assertThat(facts.length()).isLessThanOrEqualTo(13_000);
    }

    @Test
    void aCharBudgetCutsRowsThatTheRowCountAloneWouldKeep() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        List<CatalogItem> fewButHuge =
                java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(
                                i ->
                                        new CatalogItem(
                                                (long) i,
                                                "SKU-%04d".formatted(i),
                                                "N".repeat(4000),
                                                new java.math.BigDecimal("1.00"),
                                                true))
                        .toList();
        when(catalog.findAllActive()).thenReturn(fewButHuge);

        String facts = provider(catalog).facts();

        assertThat(facts.length()).isLessThanOrEqualTo(13_000);
        assertThat(facts).contains("partial");
    }

    /** 200 rows / 12 000 chars, the same knobs production gets from application.yml. */
    private static CatalogGroundingProvider provider(CatalogRepository catalog) {
        return new CatalogGroundingProvider(catalog, 200, 12_000);
    }
}
