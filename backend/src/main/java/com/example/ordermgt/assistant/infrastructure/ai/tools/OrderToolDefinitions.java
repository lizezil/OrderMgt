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
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.api.Paging;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>Note what the model may supply: skus and quantities. Never prices, never customer ids, never a
 * status other than the two the enum allows.
 */
@Configuration
public class OrderToolDefinitions {

    /** What a money row reads when the server cannot state an amount for it. */
    private static final String UNKNOWN = "—";

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
                        "List the current customer's own orders (excluding other customers).",
                        """
                        {"type":"object","properties":{}}""",
                        ordersReport,
                        (customerId, args) -> {
                            List<Map<String, Object>> rows =
                                    orderService
                                            .listByCustomer(
                                                    customerId,
                                                    Paging.FIRST_PAGE,
                                                    Paging.DEFAULT_SIZE)
                                            .items()
                                            .stream()
                                            .map(OrderToolDefinitions::summarise)
                                            .toList();
                            return ToolResult.of(write(objectMapper, rows));
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
     * 订单列表讲给顾客听的样子：一行引导句，然后每单一个块。
     *
     * <p>空结果说的是一句话，而不是把 {@code []} 摆出来；整整一页时也要声明只列了最近这些，否则 顾客会把一页当成全部历史。
     */
    private static String renderOrderList(ObjectMapper mapper, String payloadJson) {
        JsonNode orders = payload(mapper, payloadJson);
        if (!orders.isArray() || orders.isEmpty()) {
            return "你名下还没有订单。";
        }
        StringBuilder sb = new StringBuilder("你名下有 ").append(orders.size()).append(" 张订单：");
        for (JsonNode order : orders) {
            sb.append("\n\n").append(orderBlock(order));
        }
        if (orders.size() >= Paging.DEFAULT_SIZE) {
            sb.append("\n\n（只列出最近 ").append(Paging.DEFAULT_SIZE).append(" 张）");
        }
        return sb.toString();
    }

    /** 单张订单：同一个块。块首那行已经点明是哪一单，不必再另起一句引导。 */
    private static String renderOrder(ObjectMapper mapper, String payloadJson) {
        return orderBlock(payload(mapper, payloadJson));
    }

    /** 一单一块：订单号 · 中文状态 · ¥合计，下面每行一条明细。订单号与 sku 包进行内代码，其余是正文 ——等宽只留给真正的机器数据。 */
    private static String orderBlock(JsonNode order) {
        StringBuilder sb = new StringBuilder();
        sb.append('`')
                .append(order.path("orderNo").asText(""))
                .append("` · ")
                .append(statusLabel(order.path("status").asText("")))
                .append(" · ")
                .append(money(order.path("totalAmount")));
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
