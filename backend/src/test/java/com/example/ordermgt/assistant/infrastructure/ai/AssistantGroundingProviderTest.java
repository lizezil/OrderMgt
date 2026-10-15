package com.example.ordermgt.assistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.PendingAction;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.PendingActionStatus;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.AiPrompts;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Business rule: 模型不得臆造事实——服务端把提示词里需要的事实准备好（ADR-0006、ADR-0015）。
 *
 * <p>这里断言的是 facts() 的输出这一公开契约：它含哪些事实，是模型能否"说对"的根据。三类事实同一条契约——在售目录（sku 与单价）、 本会话最近一次已确认的订单（那个订单号从未进入
 * transcript，没有这条路模型就无从知道顾客说的是哪一单）， 以及本会话还没确认的提议清单（卡片自己的话，只在 PENDING 期间"活着"，US-1.13）。
 */
class AssistantGroundingProviderTest {

    private static final long CUSTOMER = 1001L;
    private static final long CONVERSATION = 7L;

    @Test
    void factsCarryEveryActiveSkuWithItsUnitPrice() {
        CatalogRepository catalog =
                catalog(
                        item(1L, "WIDGET-01", "Widget", "19.90"),
                        item(2L, "GIZMO-03", "Gizmo", "4.50"));

        String facts = provider(catalog, noConfirmedAction()).facts(CUSTOMER, CONVERSATION);

        // 每个在售商品都要把 sku 与单价摆到模型面前，它才能照抄而不是编一个。
        assertThat(facts).contains("WIDGET-01").contains("19.90");
        assertThat(facts).contains("GIZMO-03").contains("4.50");
    }

    @Test
    void factsWithNothingActiveInventNoItems() {
        String facts = provider(catalog(), noConfirmedAction()).facts(CUSTOMER, CONVERSATION);

        // 目录为空时宁可什么都不说，也不能凭空列出一个商品。
        assertThat(facts).doesNotContain("unit price");
    }

    /**
     * US-4.6：助手报的商品与单价和 {@code GET /api/catalog/items} 是同一个读——两边都走 {@code
     * findAllActive()}，"上架集"只有这一处来源，报价与接口不可能是两个目录。
     */
    @Test
    void theCatalogTheAssistantQuotesIsTheSameReadTheCatalogEndpointServes() {
        CatalogRepository catalog = catalog(item(1L, "WIDGET-01", "Widget", "19.90"));

        provider(catalog, noConfirmedAction()).facts(CUSTOMER, CONVERSATION);

        verify(catalog).findAllActive();
    }

    /**
     * 上限是上下文窗口的生存问题：目录涨到几千条时，无上限的 facts() 会把每次调用都变成一次 context-length
     * 400——助手不是降级，是整体报废。行数与字符双保险，超出即截断并声明是部分视图。
     */
    @Test
    void factsAreCappedSoAVastCatalogCannotBlowTheContextWindow() {
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

        String facts = provider(catalog(many), noConfirmedAction()).facts(CUSTOMER, CONVERSATION);

        assertThat(facts).contains("SKU-0001");
        assertThat(facts).doesNotContain("SKU-0201");
        assertThat(facts).contains("partial");
        assertThat(facts.length()).isLessThanOrEqualTo(13_000);
    }

    @Test
    void aCharBudgetCutsRowsThatTheRowCountAloneWouldKeep() {
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

        String facts =
                provider(catalog(fewButHuge), noConfirmedAction()).facts(CUSTOMER, CONVERSATION);

        assertThat(facts.length()).isLessThanOrEqualTo(13_000);
        assertThat(facts).contains("partial");
    }

    /** 顾客说「刚刚那个订单」时，那个订单号只在已确认动作的结果引用上——正文里没有它。所以它必须由服务端事实带进提示词，否则模型只能列出全部订单、 猜顾客指的是哪一单。 */
    @Test
    void aConversationThatConfirmedAnOrderHandsTheModelItsNumber() {
        PendingAction confirmed = confirmedAction("ORD2026101421214197C492");
        String facts =
                provider(
                                catalog(item(1L, "WIDGET-01", "Widget", "19.90")),
                                pendingActions(Optional.of(confirmed)))
                        .facts(CUSTOMER, CONVERSATION);

        assertThat(facts).contains(AiPrompts.LAST_CONFIRMED_ORDER_HEADER);
        assertThat(facts).contains(confirmed.getResultRef());
        // 订单号之外还带上卡片那句话，模型才知道这一单下的是什么。
        assertThat(facts).contains(confirmed.getSummary());
    }

    /** 一段还没确认过任何动作的会话，事实里不该出现这一行——空标题也是话，模型会照着它答一个不存在的订单。 */
    @Test
    void aConversationWithNothingConfirmedSaysNothingAboutTheLastOrder() {
        String facts =
                provider(catalog(item(1L, "WIDGET-01", "Widget", "19.90")), noConfirmedAction())
                        .facts(CUSTOMER, CONVERSATION);

        assertThat(facts)
                .doesNotContain(AiPrompts.LAST_CONFIRMED_ORDER_HEADER)
                .doesNotContain("ORD");
    }

    /** 「就办刚才那个」在卡片还没确认时也要有确定答案：清单按提议顺序，工具名 + 卡片自己那句话，绝不带 token。 */
    @Test
    void liveProposalsAreHandedOverWithTheirToolAndOwnWords() {
        PendingAction first = proposed("createOrder", "Create order: GIZMO-03 × 3");
        PendingAction second = proposed("cancelOrder", "Cancel order ORD2026101421214197C492");

        String facts =
                provider(
                                catalog(item(1L, "WIDGET-01", "Widget", "19.90")),
                                pendingActions(Optional.empty(), first, second))
                        .facts(CUSTOMER, CONVERSATION);

        assertThat(facts).contains(AiPrompts.AWAITING_CONFIRMATION_HEADER);
        assertThat(facts).contains("createOrder").contains(first.getSummary());
        assertThat(facts).contains("cancelOrder").contains(second.getSummary());
        // 提案顺序保持：清单读起来和线程一致，「第二张」才数得对。
        assertThat(facts.indexOf(first.getSummary()))
                .isLessThan(facts.indexOf(second.getSummary()));
        // 客户侧凭证不是模型需要的事实。
        assertThat(facts).doesNotContain(first.getToken()).doesNotContain(second.getToken());
    }

    /** 没有待确认卡片的会话，这一段不出现——空标题同样是话，模型会照着它答不存在的卡片。 */
    @Test
    void aConversationWithNothingAwaitingSaysNothingAboutProposals() {
        String facts =
                provider(catalog(item(1L, "WIDGET-01", "Widget", "19.90")), noConfirmedAction())
                        .facts(CUSTOMER, CONVERSATION);

        assertThat(facts).doesNotContain(AiPrompts.AWAITING_CONFIRMATION_HEADER);
    }

    /** 两段并存、互不覆盖：「刚刚这个订单」解析到已确认的那一单，「刚才那个提议」解析到待确认清单的最后一张。 */
    @Test
    void theConfirmedOrderAndTheAwaitingProposalsCoexist() {
        PendingAction confirmed = confirmedAction("ORD2026101421214197C492");
        PendingAction awaiting = proposed("createOrder", "Create order: GIZMO-03 × 1");

        String facts =
                provider(
                                catalog(item(1L, "WIDGET-01", "Widget", "19.90")),
                                pendingActions(Optional.of(confirmed), awaiting))
                        .facts(CUSTOMER, CONVERSATION);

        assertThat(facts)
                .contains(AiPrompts.LAST_CONFIRMED_ORDER_HEADER)
                .contains(confirmed.getResultRef())
                .contains(AiPrompts.AWAITING_CONFIRMATION_HEADER)
                .contains(awaiting.getSummary());
    }

    /** 200 rows / 12 000 chars, the same knobs production gets from application.yml. */
    private static AssistantGroundingProvider provider(
            CatalogRepository catalog, PendingActionRepository pendingActions) {
        return new AssistantGroundingProvider(catalog, pendingActions, 200, 12_000);
    }

    private static CatalogRepository catalog(CatalogItem... items) {
        return catalog(List.of(items));
    }

    private static CatalogRepository catalog(List<CatalogItem> items) {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findAllActive()).thenReturn(items);
        return catalog;
    }

    private static CatalogItem item(long id, String sku, String name, String price) {
        return new CatalogItem(id, sku, name, new BigDecimal(price), true);
    }

    /** A repository whose conversation may have a confirmed action and proposals still waiting. */
    private static PendingActionRepository pendingActions(
            Optional<PendingAction> latest, PendingAction... awaiting) {
        PendingActionRepository repository = mock(PendingActionRepository.class);
        when(repository.findLatestConfirmed(any(), any())).thenReturn(latest);
        when(repository.findLivePending(any(), any(), any())).thenReturn(List.of(awaiting));
        return repository;
    }

    /** A conversation that has confirmed nothing: the last-order fact simply is not there. */
    private static PendingActionRepository noConfirmedAction() {
        return pendingActions(Optional.empty());
    }

    private static PendingAction proposed(String toolName, String summary) {
        return PendingAction.propose(
                CUSTOMER, CONVERSATION, toolName, "{}", summary, Duration.ofMinutes(15));
    }

    private static PendingAction confirmedAction(String orderNumber) {
        PendingAction action =
                PendingAction.propose(
                        CUSTOMER,
                        CONVERSATION,
                        "createOrder",
                        "{\"lines\":[]}",
                        "Place order: GIZMO-03 × 1",
                        Duration.ofMinutes(15));
        action.resolve(PendingActionStatus.CONFIRMED, orderNumber, Instant.now());
        return action;
    }
}
