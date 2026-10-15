package com.example.ordermgt.assistant.infrastructure.ai.tools;

import com.example.ordermgt.assistant.domain.ToolCard;
import com.example.ordermgt.assistant.domain.ToolField;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolReport;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.order.application.LineCommand;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Money;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderFilter;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.api.Paging;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tool whitelist.
 *
 * <p>Every spec carries a handler, but only the readonly ones are reachable from {@code
 * AssistantService}. A mutating handler is reached exclusively through {@code
 * ActionConfirmationService}, i.e. after the customer confirmed the card.
 *
 * <p>A mutating spec also carries its {@link ToolCard}: title, one-line summary and labelled rows
 * all live here, beside the schema and the handler, so adding a tool is a single edit.
 *
 * <p>Note what the model may supply: skus, quantities and, for the readonly lookups, a {@code
 * limit}. Never prices, never customer ids, never a status other than the two the enum allows.
 */
@Configuration
public class OrderToolDefinitions {

    /** What a money row reads when the server cannot state an amount for it. */
    private static final String UNKNOWN = "—";

    /**
     * 下单时间讲给顾客听的样子。用时区与订单号同一个 {@link OrderNumber#ZONE}：两处若各写一处， 「最近一单」的时间就会和它自己的
     * 订单号差八小时，而顾客是能看见这两个的。
     */
    private static final DateTimeFormatter PLACED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(OrderNumber.ZONE);

    /**
     * The whitelist over these definitions. Wired here so the domain class stays framework-free.
     */
    @Bean
    public ToolRegistry toolRegistry(List<ToolSpec> specs) {
        return new ToolRegistry(specs);
    }

    @Bean
    public List<ToolSpec> orderToolSpecs(
            OrderService orderService, ObjectMapper objectMapper, CatalogRepository catalog) {
        // 两个只读工具讲给顾客听的样子。声明在这里，和 schema、handler 挨着——加一个工具仍然是一次编辑。
        ToolReport ordersReport = payload -> renderOrderList(objectMapper, payload);
        ToolReport orderReport = payload -> renderOrder(objectMapper, payload);
        return List.of(
                ToolSpec.readonly(
                        "queryMyOrders",
                        "List the current customer's own orders (excluding other customers). "
                                + "Optional filters narrow the list; the summary always covers every "
                                + "matching order, not just the ones listed.",
                        """
                        {"type":"object","properties":{"limit":{"type":"integer","description":"How many of the matching orders to list, 1-%d. Applied after the filters. Use 1 when the customer asks for their latest single order. Defaults to %d.","minimum":1,"maximum":%d},"status":{"type":"string","enum":["CREATED","COMPLETED","CANCELLED"],"description":"Only orders in this status. Omit for every status."},"from":{"type":"string","description":"Only orders placed on or after this date, YYYY-MM-DD."},"to":{"type":"string","description":"Only orders placed on or before this date, YYYY-MM-DD. Both ends are inclusive."}}}"""
                                .formatted(
                                        Paging.DEFAULT_SIZE, Paging.DEFAULT_SIZE, Paging.MAX_SIZE),
                        ordersReport,
                        (customerId, args) -> {
                            // 先按条件收窄、再取 N 张；汇总算的是**全部命中**，不是这一页——顾客问
                            // "上个月一共多少钱"，那个数不能随 limit 变（US-4.5）。
                            OrderFilter filter = readFilter(objectMapper, args);
                            PageResult<Order> page =
                                    orderService.listByCustomer(
                                            customerId,
                                            filter,
                                            Paging.FIRST_PAGE,
                                            readLimit(objectMapper, args));
                            List<Map<String, Object>> rows =
                                    page.items().stream()
                                            .map(OrderToolDefinitions::summarise)
                                            .toList();
                            Map<String, Object> envelope = new LinkedHashMap<>();
                            envelope.put("total", page.total());
                            envelope.put("returned", rows.size());
                            envelope.put(
                                    "totalAmount",
                                    orderService
                                            .totalAmountByCustomer(customerId, filter)
                                            .amount());
                            String scope = filterPhrase(filter);
                            if (!scope.isEmpty()) {
                                envelope.put("filteredBy", scope);
                            }
                            envelope.put("orders", rows);
                            return ToolResult.of(write(objectMapper, envelope));
                        }),
                ToolSpec.readonly(
                        "getOrderByNumber",
                        "Look up one of the current customer's own orders by order number.",
                        """
                        {"type":"object","properties":{"orderNumber":{"type":"string","description":"Order number"}},"required":["orderNumber"]}""",
                        orderReport,
                        (customerId, args) -> {
                            String orderNumber = requireText(objectMapper, args, "orderNumber");
                            return ToolResult.of(
                                    write(
                                            objectMapper,
                                            summarise(
                                                    orderService.getByNumber(
                                                            customerId, orderNumber))));
                        }),
                ToolSpec.mutating(
                        "createOrder",
                        "Create a new order for the customer. Prices are set by the "
                                + "system from the sku; do not supply prices.",
                        """
                        {"type":"object","properties":{"lines":{"type":"array","minItems":1,"items":{"type":"object","properties":{"sku":{"type":"string","description":"SKU"},"quantity":{"type":"integer","minimum":1}},"required":["sku","quantity"]}}},"required":["lines"]}""",
                        new ToolCard(
                                "Create Order",
                                args -> describeLines(objectMapper, args),
                                args -> lineFields(objectMapper, catalog, args)),
                        (customerId, args) -> {
                            Order order =
                                    orderService.createOrder(
                                            customerId, parseLines(objectMapper, args));
                            return ToolResult.of(
                                    write(objectMapper, summarise(order)),
                                    order.getOrderNumber().value());
                        }),
                ToolSpec.mutating(
                        "updateOrderStatus",
                        "Change the order status to COMPLETED or CANCELLED.",
                        """
                        {"type":"object","properties":{"orderNumber":{"type":"string"},"targetStatus":{"type":"string","enum":["COMPLETED","CANCELLED"]}},"required":["orderNumber","targetStatus"]}""",
                        new ToolCard(
                                "Change Order Status",
                                args ->
                                        "Change order "
                                                + text(objectMapper, args, "orderNumber")
                                                + " to "
                                                + text(objectMapper, args, "targetStatus"),
                                args ->
                                        List.of(
                                                new ToolField(
                                                        "Order number",
                                                        textOrDash(
                                                                objectMapper, args, "orderNumber")),
                                                new ToolField(
                                                        "Target status",
                                                        textOrDash(
                                                                objectMapper,
                                                                args,
                                                                "targetStatus")))),
                        (customerId, args) -> {
                            Order order =
                                    orderService.changeStatus(
                                            customerId,
                                            requireText(objectMapper, args, "orderNumber"),
                                            targetStatus(objectMapper, args));
                            return ToolResult.of(
                                    write(objectMapper, summarise(order)),
                                    order.getOrderNumber().value());
                        }),
                ToolSpec.mutating(
                        "cancelOrder",
                        "Cancel an order.",
                        """
                        {"type":"object","properties":{"orderNumber":{"type":"string"}},"required":["orderNumber"]}""",
                        new ToolCard(
                                "Cancel Order",
                                args -> "Cancel order " + text(objectMapper, args, "orderNumber"),
                                args ->
                                        List.of(
                                                new ToolField(
                                                        "Order number",
                                                        textOrDash(
                                                                objectMapper,
                                                                args,
                                                                "orderNumber")))),
                        (customerId, args) -> {
                            Order order =
                                    orderService.changeStatus(
                                            customerId,
                                            requireText(objectMapper, args, "orderNumber"),
                                            OrderStatus.CANCELLED);
                            return ToolResult.of(
                                    write(objectMapper, summarise(order)),
                                    order.getOrderNumber().value());
                        }));
    }

    /** The shape the model is asked for: sku + quantity, nothing else. */
    private static List<LineCommand> parseLines(ObjectMapper mapper, String args) {
        JsonNode lines = root(mapper, args).path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "lines must be a non-empty array");
        }
        List<LineCommand> commands = new ArrayList<>();
        for (JsonNode line : lines) {
            String sku = line.path("sku").asText("");
            int quantity = line.path("quantity").asInt(0);
            if (sku.isBlank() || quantity < 1) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_FAILED, "each line needs a sku and quantity >= 1");
            }
            commands.add(new LineCommand(sku, quantity));
        }
        return commands;
    }

    private static String describeLines(ObjectMapper mapper, String args) {
        StringBuilder sb = new StringBuilder("Place order: ");
        boolean first = true;
        for (LineCommand command : parseLines(mapper, args)) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(command.sku()).append(" × ").append(command.quantity());
        }
        return sb.toString();
    }

    /**
     * One Item / Quantity / Unit price / Line total block per ordered line, closed by the order
     * total, so the customer sees what they are about to pay before authorising -- and every amount
     * is the catalog's, not the model's word.
     */
    private static List<ToolField> lineFields(
            ObjectMapper mapper, CatalogRepository catalog, String args) {
        List<ToolField> fields = new ArrayList<>();
        JsonNode lines = root(mapper, args).path("lines");
        if (!lines.isArray()) {
            return fields;
        }
        Money total = Money.ZERO;
        boolean everyLinePriced = true;
        for (JsonNode line : lines) {
            String sku = line.path("sku").asText("");
            int quantity = line.path("quantity").asInt(0);
            Optional<Money> price = unitPrice(catalog, sku);
            // An unpriceable or non-positive line has no amount -- quoting ¥0.00 for it would read
            // as a free item rather than as the bad request the confirmation will reject.
            Optional<Money> lineTotal =
                    price.filter(ignored -> quantity >= 1).map(unit -> unit.multiply(quantity));
            fields.add(new ToolField("Item", sku.isBlank() ? UNKNOWN : sku));
            fields.add(new ToolField("Quantity", String.valueOf(quantity)));
            fields.add(
                    new ToolField(
                            "Unit price", price.map(OrderToolDefinitions::amount).orElse(UNKNOWN)));
            fields.add(
                    new ToolField(
                            "Line total",
                            lineTotal.map(OrderToolDefinitions::amount).orElse(UNKNOWN)));
            if (lineTotal.isPresent()) {
                total = total.plus(lineTotal.get());
            } else {
                everyLinePriced = false;
            }
        }
        if (!fields.isEmpty()) {
            // A partial sum is not a total: if one line could not be priced, the customer cannot be
            // told what this order costs, so the row says so instead of understating the charge.
            fields.add(new ToolField("Total", everyLinePriced ? amount(total) : UNKNOWN));
        }
        return fields;
    }

    /**
     * The catalog's price for a sku: the only price source there is. Empty when the catalog does
     * not carry the sku (or has discontinued it) -- an unknown line reads as {@link #UNKNOWN} and
     * is rejected on confirmation, rather than hiding the whole card behind the raw-arguments
     * fallback, which would drop the item and quantity too.
     */
    private static Optional<Money> unitPrice(CatalogRepository catalog, String sku) {
        if (sku.isBlank()) {
            return Optional.empty();
        }
        return catalog.findBySku(sku)
                .filter(CatalogItem::active)
                .map(item -> Money.of(item.unitPrice()));
    }

    private static String amount(Money money) {
        return "¥" + money.amount().toPlainString();
    }

    private static Map<String, Object> summarise(Order order) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderNo", order.getOrderNumber().value());
        // 给机器的那一份是 ISO 瞬时；人话那份才换成时区写法。顾客问"最近一次"时，时间是答案的一部分，
        // 而它此前只藏在订单号里那串数字中。
        row.put("createdAt", order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
        row.put("status", order.getStatus().name());
        row.put("totalAmount", order.getTotalAmount().amount());
        row.put(
                "lines",
                order.getLines().stream()
                        .map(
                                l ->
                                        Map.of(
                                                "sku",
                                                l.sku(),
                                                "itemName",
                                                l.itemName(),
                                                "quantity",
                                                l.quantity(),
                                                "lineAmount",
                                                l.lineAmount().amount()))
                        .toList());
        return row;
    }

    /**
     * 订单列表讲给顾客听的样子：一行引导，然后每单一个块。
     *
     * <p>空结果说的是一句话，而不是把 {@code []} 摆出来。引导句同时说清两件事——一共有多少、这里列了几张， 以及**最近一张是哪一单**。后者不是装饰：
     * 顾客问「查询最近一次下单」时，模型往往只调用工具、一个字都不写， 回复就完全由这里组成；不点名，顾客收到的就是一页无人排序的清单。列表本就按下单时间倒序，所以第一张
     * 一定是最近的那一张，服务端说得出这句话，不等模型想起来 {@code limit}。
     */
    private static String renderOrderList(ObjectMapper mapper, String payloadJson) {
        JsonNode payload = payload(mapper, payloadJson);
        JsonNode orders = payload.path("orders");
        String scope = payload.path("filteredBy").asText("");
        if (!orders.isArray() || orders.isEmpty()) {
            return scope.isEmpty() ? "你名下还没有订单。" : "你名下没有" + scope + "的订单。";
        }
        long total = payload.path("total").asLong(orders.size());
        StringBuilder sb = new StringBuilder();
        sb.append(scope.isEmpty() ? "你名下" : "你名下" + scope + "的")
                .append("共 ")
                .append(total)
                .append(" 张订单，合计 ")
                .append(money(payload.path("totalAmount")));
        if (total > orders.size()) {
            // 超过一页要给一条出路（US-4.5）：说得出还有多少张没列出，而不是把顾客堵在"最近 K 张"里。
            sb.append("；这里列出最近 ")
                    .append(orders.size())
                    .append(" 张，还有 ")
                    .append(total - orders.size())
                    .append(" 张未列出");
        }
        sb.append("，最近一张是 `").append(orders.get(0).path("orderNo").asText("")).append("`：");
        for (JsonNode order : orders) {
            sb.append("\n\n").append(orderBlock(order));
        }
        return sb.toString();
    }

    /** 单张订单：同一个块。块首那行已经点明是哪一单，不必再另起一句引导。 */
    private static String renderOrder(ObjectMapper mapper, String payloadJson) {
        return orderBlock(payload(mapper, payloadJson));
    }

    /** 一单一块：订单号 · 下单时间 · 中文状态 · ¥合计，下面每行一条明细。订单号与 sku 包进行内代码，其余是正文 ——等宽只留给真正的机器数据。 */
    private static String orderBlock(JsonNode order) {
        List<String> head = new ArrayList<>();
        head.add('`' + order.path("orderNo").asText("") + '`');
        String placedAt = placedAt(order.path("createdAt").asText(""));
        if (!placedAt.isBlank()) {
            head.add(placedAt);
        }
        head.add(statusLabel(order.path("status").asText("")));
        head.add(money(order.path("totalAmount")));
        StringBuilder sb = new StringBuilder(String.join(" · ", head));
        for (JsonNode line : order.path("lines")) {
            sb.append("\n- `")
                    .append(line.path("sku").asText(""))
                    .append("` ")
                    .append(line.path("itemName").asText(""))
                    .append(" × ")
                    .append(line.path("quantity").asInt())
                    .append(" = ")
                    .append(money(line.path("lineAmount")));
        }
        return sb.toString();
    }

    /**
     * ISO 瞬时换成顾客的读法；读不通就原样透出，宁可让顾客看见一个奇怪的字符串，也不替他编一个时间。
     *
     * <p>缺失则是一段空的：块首那行少一节 {@code · 时间}，而不是出现 {@code · · }。
     */
    private static String placedAt(String raw) {
        if (raw.isBlank()) {
            return "";
        }
        try {
            return PLACED_AT.format(Instant.parse(raw));
        } catch (DateTimeParseException ex) {
            return raw;
        }
    }

    /** 状态的顾客语言；枚举之外的取值原样透出，而不是替它编一个说法。 */
    private static String statusLabel(String raw) {
        try {
            return switch (OrderStatus.valueOf(raw)) {
                case CREATED -> "已创建";
                case COMPLETED -> "已完成";
                case CANCELLED -> "已取消";
            };
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    /**
     * The payload went through JSON on its way here, and a JSON round trip parses {@code 19.90}
     * into a double -- which reads back as {@code 19.9}. Routing through {@link Money} restores the
     * scale the domain fixed for prices, so a rendered amount always looks like one.
     */
    private static String money(JsonNode amount) {
        return amount.isNumber()
                ? "¥" + Money.of(amount.decimalValue()).amount().toPlainString()
                : UNKNOWN;
    }

    /**
     * 把负载读回来。它是本类刚用 {@link #write} 写出去的，所以读不回来是我们自己的 bug，而不是 请求有问题——因此报
     * INTERNAL_ERROR，而不是模型参数写错时该报的那个校验错误。
     */
    private static JsonNode payload(ObjectMapper mapper, String payloadJson) {
        try {
            return mapper.readTree(payloadJson);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "failed to read a tool payload back");
        }
    }

    /**
     * The model can ignore the schema's enum, so an unknown status is a bad request to reject --
     * not an {@link IllegalArgumentException} that would surface as a 500.
     */
    private static OrderStatus targetStatus(ObjectMapper mapper, String args) {
        String raw = requireText(mapper, args, "targetStatus");
        try {
            return OrderStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "unsupported target status: " + raw);
        }
    }

    /**
     * 模型可以无视 schema 的 enum，也可以无视 {@code minimum}/{@code maximum}：越界的 {@code limit}
     * 报校验错误，而不是钳制。钳制是把顾客的错悄悄改成另一个答案——顾客问"最近一次"却拿回 20 张，而模型永远不知道 自己被改了；报错了，上层还能把这个错误回喂给它重问一次。
     */
    private static long readLimit(ObjectMapper mapper, String args) {
        JsonNode limit = root(mapper, args).path("limit");
        if (limit.isMissingNode() || limit.isNull()) {
            return Paging.DEFAULT_SIZE;
        }
        if (!limit.isIntegralNumber()
                || limit.longValue() < 1
                || limit.longValue() > Paging.MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "limit must be an integer between 1 and " + Paging.MAX_SIZE);
        }
        return limit.longValue();
    }

    /**
     * 收窄条件。模型可以无视 schema 与枚举，所以非法状态、写不通的日期、{@code to} 早于 {@code from} 都报 {@code
     * VALIDATION_FAILED}（US-1.9 那套：拒绝并回喂重问），而不是钳成一个顾客没问过的答案。
     */
    private static OrderFilter readFilter(ObjectMapper mapper, String args) {
        JsonNode root = root(mapper, args);
        OrderStatus status = readStatus(root.path("status"));
        Instant from = readDay(root.path("from"), "from", false);
        Instant to = readDay(root.path("to"), "to", true);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
        }
        return new OrderFilter(status, from, to);
    }

    private static OrderStatus readStatus(JsonNode status) {
        String raw = status.asText("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "unsupported order status: " + raw);
        }
    }

    /**
     * 一天翻成一个瞬时：{@code from} 取当天零点（闭），{@code to} 取**次日**零点（开）——"上个月下单的" 里的日期是顾客本地日历上的一天，不是 24
     * 小时里的某个点。时区与订单号同一个 {@link OrderNumber#ZONE}， 否则顾客报的日期会和订单号上的日期差八小时。
     */
    private static Instant readDay(JsonNode day, String field, boolean exclusiveEnd) {
        String raw = day.asText("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            return (exclusiveEnd ? date.plusDays(1) : date)
                    .atStartOfDay(OrderNumber.ZONE)
                    .toInstant();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, field + " must be a date like 2026-10-15");
        }
    }

    /** 收窄条件讲给顾客听的样子；没有条件就是一段空的，引导句里不留一个空括号。 */
    private static String filterPhrase(OrderFilter filter) {
        List<String> parts = new ArrayList<>();
        if (filter.status() != null) {
            parts.add("状态为" + statusLabel(filter.status().name()));
        }
        if (filter.fromInclusive() != null) {
            parts.add(dayText(filter.fromInclusive()) + " 起");
        }
        if (filter.toExclusive() != null) {
            // 右端是开区间，说的那一天要退回来：顾客说的"到 9 月 30 日"存的次日零点。
            parts.add(dayText(filter.toExclusive().minus(Duration.ofDays(1))) + " 止");
        }
        return String.join("、", parts);
    }

    private static String dayText(Instant instant) {
        return LocalDate.ofInstant(instant, OrderNumber.ZONE).toString();
    }

    private static String requireText(ObjectMapper mapper, String args, String field) {
        String value = text(mapper, args, field);
        if (value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "missing argument: " + field);
        }
        return value;
    }

    private static String text(ObjectMapper mapper, String args, String field) {
        return root(mapper, args).path(field).asText("");
    }

    private static String textOrDash(ObjectMapper mapper, String args, String field) {
        String value = text(mapper, args, field);
        return value.isBlank() ? "—" : value;
    }

    private static JsonNode root(ObjectMapper mapper, String args) {
        try {
            return mapper.readTree(args);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "tool arguments are not valid JSON");
        }
    }

    private static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "failed to serialize result");
        }
    }
}
