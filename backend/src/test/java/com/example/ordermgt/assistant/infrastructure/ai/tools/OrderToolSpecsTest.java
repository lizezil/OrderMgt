package com.example.ordermgt.assistant.infrastructure.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.order.application.LineCommand;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Money;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderFilter;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.api.Paging;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Business rule: 每个工具真正去做的那件事。
 *
 * <p>模型只提供"工具名 + 结构化参数"；参数怎么变成业务动作，由这里的 handler 决定。所以要验证 createOrder 只把 sku
 * 与数量交给下单服务（价格不由模型决定）、取消/改状态确实走状态机、以及模型 给出的非法参数会被拒绝成 400 而不是把服务打崩。
 */
class OrderToolSpecsTest {

    private static final Long CUSTOMER = 1001L;

    /** 钉住下单时间，渲染出来的那一段才可整行比对；Asia/Shanghai 下是 14:41。 */
    private static final Instant ORDERED_AT = Instant.parse("2026-10-15T06:41:34Z");

    private OrderService orderService;

    private Map<String, ToolSpec> specs;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        specs =
                new OrderToolDefinitions()
                                .orderToolSpecs(
                                        orderService,
                                        new ObjectMapper(),
                                        mock(CatalogRepository.class))
                                .stream()
                                .collect(Collectors.toMap(ToolSpec::name, Function.identity()));
    }

    /**
     * US-4.6：工具箱恰好是白名单五件——没有 {@code listCatalog} 是定夺而非缺口（ADR-0006）： 目录由 grounding
     * 每次注入，工具数量本身就是模型每次都要读的说明书。
     */
    @Test
    void theToolboxIsExactlyTheFiveWhitelistedToolsWithNoCatalogListing() {
        assertThat(specs.keySet())
                .containsExactlyInAnyOrder(
                        "queryMyOrders",
                        "getOrderByNumber",
                        "createOrder",
                        "updateOrderStatus",
                        "cancelOrder");
    }

    @Test
    void createOrderPassesOnlySkuAndQuantityForServerSidePricing() {
        when(orderService.createOrder(eq(CUSTOMER), anyList()))
                .thenReturn(order(OrderStatus.CREATED));

        ToolResult result =
                execute("createOrder", "{\"lines\":[{\"sku\":\"GIZMO-03\",\"quantity\":3}]}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LineCommand>> lines = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(eq(CUSTOMER), lines.capture());
        // 参数里只有 sku 与数量，没有任何金额字段——价格只能来自 catalog。
        assertThat(lines.getValue()).containsExactly(new LineCommand("GIZMO-03", 3));
        assertThat(result.ref()).isEqualTo("ORD-1");
        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
    }

    @Test
    void createOrderRejectsMissingQuantity() {
        assertThatThrownBy(() -> execute("createOrder", "{\"lines\":[{\"sku\":\"GIZMO-03\"}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void cancelOrderUsesStateMachineToCancel() {
        when(orderService.changeStatus(CUSTOMER, "ORD-1", OrderStatus.CANCELLED))
                .thenReturn(order(OrderStatus.CANCELLED));

        ToolResult result = execute("cancelOrder", "{\"orderNumber\":\"ORD-1\"}");

        verify(orderService).changeStatus(CUSTOMER, "ORD-1", OrderStatus.CANCELLED);
        assertThat(result.ref()).isEqualTo("ORD-1");
    }

    @Test
    void updateOrderStatusPassesValidTargetStatusToStateMachine() {
        when(orderService.changeStatus(CUSTOMER, "ORD-1", OrderStatus.COMPLETED))
                .thenReturn(order(OrderStatus.COMPLETED));

        execute("updateOrderStatus", "{\"orderNumber\":\"ORD-1\",\"targetStatus\":\"COMPLETED\"}");

        verify(orderService).changeStatus(CUSTOMER, "ORD-1", OrderStatus.COMPLETED);
    }

    @Test
    void updateOrderStatusRejectsInvalidTargetStatusAsParameterError() {
        // 模型可以无视 schema 的 enum：服务端必须把不认识的枚举值挡成 400，而不是让它变成一次 500。
        assertThatThrownBy(
                        () ->
                                execute(
                                        "updateOrderStatus",
                                        "{\"orderNumber\":\"ORD-1\",\"targetStatus\":\"BOGUS\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void queryMyOrdersReturnsOnlyCurrentCustomerOrderSummaries() {
        stubOrders(List.of(order(OrderStatus.CREATED)), 1, "19.90");

        ToolResult result = execute("queryMyOrders", "{}");

        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
        assertThat(result.payloadJson()).contains("\"WIDGET-01\"");
    }

    @Test
    void queryMyOrdersHonoursALimitAndStillReportsTheWholeTotal() {
        stubOrders(List.of(order(OrderStatus.CREATED)), 3, "59.70");

        ToolResult result = execute("queryMyOrders", "{\"limit\":1}");

        // 顾客问"最近一次下单"时，模型要能只取一张——这是它此前唯一办不到的事。
        verify(orderService).listByCustomer(eq(CUSTOMER), any(), eq(Paging.FIRST_PAGE), eq(1L));
        // limit 只裁列出的张数；"一共几张"与"合计多少"都是全库的，不随这一页缩水。
        assertThat(result.payloadJson()).startsWith("{\"total\":3,\"returned\":1,");
        assertThat(report("queryMyOrders", result))
                .startsWith("你名下共 3 张订单，合计 ¥59.70；这里列出最近 1 张，还有 2 张未列出，" + "最近一张是 `ORD-1`：");
    }

    @Test
    void queryMyOrdersOffersTheLimitToTheModelInTheSchema() {
        // schema 是模型唯一能看见的说明书：没有 limit，它就只能要么不查、要么查一整页。
        assertThat(specs.get("queryMyOrders").inputSchemaJson())
                .contains("\"limit\"")
                .contains("\"minimum\":1")
                .contains("\"maximum\":" + Paging.MAX_SIZE);
    }

    /** US-4.5：状态与时间区间作为收窄条件交给服务端，并且**只**在这位客户的订单里筛。 */
    @Test
    void queryMyOrdersPassesStatusAndDateRangeDownAsFilters() {
        stubOrders(List.of(), 0, "0.00");
        ArgumentCaptor<OrderFilter> filter = ArgumentCaptor.forClass(OrderFilter.class);

        execute(
                "queryMyOrders",
                "{\"status\":\"CANCELLED\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}");

        verify(orderService)
                .listByCustomer(eq(CUSTOMER), filter.capture(), eq(Paging.FIRST_PAGE), eq(20L));
        assertThat(filter.getValue().status()).isEqualTo(OrderStatus.CANCELLED);
        // 两端都按顾客本地的自然日：起于 9 月 1 日零点，止于 9 月 30 日那一天的次日零点。
        assertThat(filter.getValue().fromInclusive())
                .isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(filter.getValue().toExclusive())
                .isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
        // 汇总问的是同一组条件：数字与清单必须说的是同一批订单。
        verify(orderService).totalAmountByCustomer(eq(CUSTOMER), eq(filter.getValue()));
    }

    @Test
    void queryMyOrdersWithoutConditionsNarrowsNothing() {
        stubOrders(List.of(), 0, "0.00");
        ArgumentCaptor<OrderFilter> filter = ArgumentCaptor.forClass(OrderFilter.class);

        execute("queryMyOrders", "{}");

        verify(orderService).listByCustomer(eq(CUSTOMER), filter.capture(), anyLong(), anyLong());
        assertThat(filter.getValue().isEmpty()).isTrue();
    }

    /** 非法状态不钳制：钳成 CREATED 会把顾客问的东西悄悄换掉，而模型永远不知道自己被改了。 */
    @Test
    void queryMyOrdersRejectsAnUnknownStatusInsteadOfClampingIt() {
        assertThatThrownBy(() -> execute("queryMyOrders", "{\"status\":\"SHIPPED\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void queryMyOrdersRejectsAnUnreadableDate() {
        assertThatThrownBy(() -> execute("queryMyOrders", "{\"from\":\"last monday\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void queryMyOrdersRejectsARangeThatEndsBeforeItStarts() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        "queryMyOrders",
                                        "{\"from\":\"2026-09-30\",\"to\":\"2026-09-01\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    /** 收窄后的引导句说清两件事：这是按什么筛的，以及还有多少张没列出来（US-4.5）。 */
    @Test
    void aNarrowedListNamesItsConditionsAndStillOffersAWayOut() {
        stubOrders(
                List.of(
                        order("ORD-7", OrderStatus.CANCELLED),
                        order("ORD-6", OrderStatus.CANCELLED)),
                5,
                "99.50");

        String rendered =
                report("queryMyOrders", execute("queryMyOrders", "{\"status\":\"CANCELLED\"}"));

        assertThat(rendered)
                .startsWith("你名下状态为已取消的共 5 张订单，合计 ¥99.50；这里列出最近 2 张，" + "还有 3 张未列出，最近一张是 `ORD-7`：");
        assertThat(rendered).contains("`ORD-7` · 2026-10-15 14:41 · 已取消 · ¥19.90");
    }

    @Test
    void aNarrowedListWithNoHitsSaysSoInTheCustomersTerms() {
        stubOrders(List.of(), 0, "0.00");

        assertThat(report("queryMyOrders", execute("queryMyOrders", "{\"status\":\"COMPLETED\"}")))
                .isEqualTo("你名下没有状态为已完成的订单。");
    }

    @Test
    void queryMyOrdersOffersTheNarrowingKnobsToTheModelInTheSchema() {
        assertThat(specs.get("queryMyOrders").inputSchemaJson())
                .contains("\"status\"")
                .contains("\"CREATED\"")
                .contains("\"COMPLETED\"")
                .contains("\"CANCELLED\"")
                .contains("\"from\"")
                .contains("\"to\"");
    }

    @Test
    void queryMyOrdersRejectsALimitBelowOne() {
        // 不钳制：把 0 悄悄换成 20 是把顾客的错改成另一个答案，而模型永远不知道自己被改了。
        assertThatThrownBy(() -> execute("queryMyOrders", "{\"limit\":0}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void queryMyOrdersRejectsALimitAboveThePageCeiling() {
        assertThatThrownBy(() -> execute("queryMyOrders", "{\"limit\":101}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void queryMyOrdersRejectsALimitThatIsNotAnInteger() {
        assertThatThrownBy(() -> execute("queryMyOrders", "{\"limit\":\"three\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void getOrderByNumberReturnsTheSummaryOfAnOrderedOrder() {
        when(orderService.getByNumber(CUSTOMER, "ORD-1")).thenReturn(order(OrderStatus.CREATED));

        ToolResult result = execute("getOrderByNumber", "{\"orderNumber\":\"ORD-1\"}");

        // 查的是发起人的单：customerId 来自登录态，模型只能给订单号，替不了别人查。
        verify(orderService).getByNumber(CUSTOMER, "ORD-1");
        assertThat(result.payloadJson()).contains("\"orderNo\":\"ORD-1\"");
        assertThat(result.payloadJson()).contains("\"WIDGET-01\"");
    }

    @Test
    void getOrderByNumberRejectsMissingOrderNumber() {
        assertThatThrownBy(() -> execute("getOrderByNumber", "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private ToolResult execute(String toolName, String argumentsJson) {
        return specs.get(toolName).handler().execute(CUSTOMER, argumentsJson);
    }

    private String report(String toolName, ToolResult result) {
        return specs.get(toolName).report().describe(result.payloadJson());
    }

    private static Order order(OrderStatus status) {
        return Order.rehydrate(
                null,
                new OrderNumber("ORD-1"),
                CUSTOMER,
                status,
                List.of(OrderLine.of("WIDGET-01", "Widget", 1, new BigDecimal("19.90"))),
                ORDERED_AT,
                ORDERED_AT,
                0);
    }

    private static Order order(String orderNumber, OrderStatus status) {
        return Order.rehydrate(
                null,
                new OrderNumber(orderNumber),
                CUSTOMER,
                status,
                List.of(OrderLine.of("WIDGET-01", "Widget", 1, new BigDecimal("19.90"))),
                ORDERED_AT,
                ORDERED_AT,
                0);
    }

    @Test
    void anEmptyOrderListSaysSoInsteadOfShowingAnEmptyArray() {
        stubOrders(List.of(), 0, "0.00");

        ToolResult result = execute("queryMyOrders", "{}");

        assertThat(report("queryMyOrders", result)).isEqualTo("你名下还没有订单。");
        // 空列表也带着 total：机器那一份任何时候都说得出"一共几张"。
        assertThat(result.payloadJson())
                .isEqualTo("{\"total\":0,\"returned\":0,\"totalAmount\":0.00,\"orders\":[]}");
    }

    @Test
    void theOrderListIsNarratedWithChineseStatusAndDollarAmounts() {
        stubOrders(List.of(order(OrderStatus.CREATED)), 1, "19.90");

        String rendered = report("queryMyOrders", execute("queryMyOrders", "{}"));

        // 报告是负载的纯投影：字段名换成人话，机器标识（订单号、sku）留在行内代码里。
        // 引导句点出最近一张与合计，块首那行报出下单时间——顾客问"最近一次"时不必自己数顺序。
        assertThat(rendered)
                .isEqualTo(
                        """
                        你名下共 1 张订单，合计 ¥19.90，最近一张是 `ORD-1`：

                        `ORD-1` · 2026-10-15 14:41 · 已创建 · ¥19.90
                        - `WIDGET-01` Widget × 1 = ¥19.90""");
    }

    @Test
    void everyStatusHasACustomerFacingLabel() {
        stubOrders(
                List.of(
                        order("ORD-1", OrderStatus.CREATED),
                        order("ORD-2", OrderStatus.COMPLETED),
                        order("ORD-3", OrderStatus.CANCELLED)),
                3,
                "59.70");

        String rendered = report("queryMyOrders", execute("queryMyOrders", "{}"));

        assertThat(rendered).contains("你名下共 3 张订单，合计 ¥59.70，最近一张是 `ORD-1`：");
        assertThat(rendered).contains("`ORD-1` · 2026-10-15 14:41 · 已创建 · ¥19.90");
        assertThat(rendered).contains("`ORD-2` · 2026-10-15 14:41 · 已完成 · ¥19.90");
        assertThat(rendered).contains("`ORD-3` · 2026-10-15 14:41 · 已取消 · ¥19.90");
    }

    @Test
    void aFullPageSaysItIsOnlyAPageNotTheWholeHistory() {
        int page = (int) Paging.DEFAULT_SIZE;
        stubOrders(
                IntStream.range(0, page)
                        .mapToObj(index -> order("ORD-" + index, OrderStatus.CREATED))
                        .toList(),
                137,
                "2726.30");

        String rendered = report("queryMyOrders", execute("queryMyOrders", "{}"));

        // 一页不是全部历史：这句话在引导句里就把"共 137、列了 20、还有 117 张"说清。
        assertThat(rendered)
                .startsWith("你名下共 137 张订单，合计 ¥2726.30；这里列出最近 20 张，还有 117 张未列出，" + "最近一张是 `ORD-0`：");
        assertThat(rendered).doesNotContain("你名下有 20 张订单");
    }

    @Test
    void theSingleOrderBlockCarriesThePlacedAtTimeToo() {
        when(orderService.getByNumber(CUSTOMER, "ORD-1")).thenReturn(order(OrderStatus.CREATED));

        assertThat(
                        report(
                                "getOrderByNumber",
                                execute("getOrderByNumber", "{\"orderNumber\":\"ORD-1\"}")))
                .isEqualTo(
                        """
                        `ORD-1` · 2026-10-15 14:41 · 已创建 · ¥19.90
                        - `WIDGET-01` Widget × 1 = ¥19.90""");
    }

    @Test
    void aSingleOrderIsNarratedWithTheSameBlockAsTheList() {
        when(orderService.getByNumber(CUSTOMER, "ORD-1")).thenReturn(order(OrderStatus.CREATED));

        String single =
                report(
                        "getOrderByNumber",
                        execute("getOrderByNumber", "{\"orderNumber\":\"ORD-1\"}"));
        stubOrders(List.of(order(OrderStatus.CREATED)), 1, "19.90");
        String firstBlockOfList =
                report("queryMyOrders", execute("queryMyOrders", "{}")).split("\n\n")[1];

        // 同一张单在两条路上必须是同一个块（US-1.3：顾客不必换一种读法）。
        assertThat(firstBlockOfList).isEqualTo(single);
    }

    /** 助手那一次只读查询：一页订单，加上命中**全体**的合计（US-4.5 的汇总由服务端算，模型不参与加总）。 一页的条数不参与合计——分页缩水不该让汇总跟着缩水。 */
    private void stubOrders(List<Order> listed, long hits, String totalAmount) {
        when(orderService.listByCustomer(eq(CUSTOMER), any(), eq(Paging.FIRST_PAGE), anyLong()))
                .thenReturn(PageResult.of(listed, hits, 1, Math.max(listed.size(), 1)));
        when(orderService.totalAmountByCustomer(eq(CUSTOMER), any()))
                .thenReturn(Money.of(new BigDecimal(totalAmount)));
    }

    @Test
    void anUnparseablePlacedAtIsPassedThroughInsteadOfInvented() {
        // 时间读不通时宁可原样透出，也不替顾客编一个：块的其余部分照常。
        String rendered =
                specs.get("queryMyOrders")
                        .report()
                        .describe(
                                """
                                {"total":1,"returned":1,"orders":[{
                                  "orderNo":"ORD-1","createdAt":"not-a-moment","status":"CREATED",
                                  "totalAmount":19.90,"lines":[]}]}""");

        assertThat(rendered).contains("`ORD-1` · not-a-moment · 已创建 · ¥19.90");
    }
}
