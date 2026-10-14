package com.example.ordermgt.assistant.infrastructure.ai.tools;

import com.example.ordermgt.assistant.domain.ToolCard;
import com.example.ordermgt.assistant.domain.ToolField;
import com.example.ordermgt.assistant.domain.ToolRegistry;
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
        return List.of(
                ToolSpec.readonly(
                        "queryMyOrders",
                        "List the current customer's own orders (excluding other customers).",
                        """
                        {"type":"object","properties":{}}""",
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
