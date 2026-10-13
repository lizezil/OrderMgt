package com.example.ordermgt.assistant.infrastructure.ai.tools;

import com.example.ordermgt.assistant.domain.ToolCard;
import com.example.ordermgt.assistant.domain.ToolField;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.assistant.domain.ToolResult;
import com.example.ordermgt.assistant.domain.ToolSpec;
import com.example.ordermgt.order.application.LineCommand;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The whitelist over these definitions. Wired here so the domain class stays framework-free.
     */
    @Bean
    public ToolRegistry toolRegistry(List<ToolSpec> specs) {
        return new ToolRegistry(specs);
    }

    @Bean
    public List<ToolSpec> orderToolSpecs(OrderService orderService, ObjectMapper objectMapper) {
        return List.of(
                ToolSpec.readonly(
                        "queryMyOrders",
                        "查询当前客户自己的订单列表（不含其他客户）。",
                        """
                        {"type":"object","properties":{}}""",
                        (customerId, args) -> {
                            List<Map<String, Object>> rows =
                                    orderService.listByCustomer(customerId, 1, 20).items().stream()
                                            .map(OrderToolDefinitions::summarise)
                                            .toList();
                            return ToolResult.of(write(objectMapper, rows));
                        }),
                ToolSpec.readonly(
                        "getOrderByNumber",
                        "按订单号查询当前客户自己的某一笔订单详情。",
                        """
                        {"type":"object","properties":{"orderNumber":{"type":"string","description":"订单号"}},"required":["orderNumber"]}""",
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
                        "为客户创建一笔新订单。价格由系统按 sku 决定，不要自行提供价格。",
                        """
                        {"type":"object","properties":{"lines":{"type":"array","minItems":1,"items":{"type":"object","properties":{"sku":{"type":"string","description":"商品编号"},"quantity":{"type":"integer","minimum":1}},"required":["sku","quantity"]}}},"required":["lines"]}""",
                        new ToolCard(
                                "创建订单",
                                args -> describeLines(objectMapper, args),
                                args -> lineFields(objectMapper, args)),
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
                        "把订单状态改为 COMPLETED 或 CANCELLED。",
                        """
                        {"type":"object","properties":{"orderNumber":{"type":"string"},"targetStatus":{"type":"string","enum":["COMPLETED","CANCELLED"]}},"required":["orderNumber","targetStatus"]}""",
                        new ToolCard(
                                "变更订单状态",
                                args ->
                                        "将订单 "
                                                + text(objectMapper, args, "orderNumber")
                                                + " 的状态改为 "
                                                + text(objectMapper, args, "targetStatus"),
                                args ->
                                        List.of(
                                                new ToolField(
                                                        "订单号",
                                                        textOrDash(
                                                                objectMapper, args, "orderNumber")),
                                                new ToolField(
                                                        "目标状态",
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
                        "取消一笔订单。",
                        """
                        {"type":"object","properties":{"orderNumber":{"type":"string"}},"required":["orderNumber"]}""",
                        new ToolCard(
                                "取消订单",
                                args -> "取消订单 " + text(objectMapper, args, "orderNumber"),
                                args ->
                                        List.of(
                                                new ToolField(
                                                        "订单号",
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "lines 必须是非空数组");
        }
        List<LineCommand> commands = new ArrayList<>();
        for (JsonNode line : lines) {
            String sku = line.path("sku").asText("");
            int quantity = line.path("quantity").asInt(0);
            if (sku.isBlank() || quantity < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每一行都需要 sku 与 >=1 的数量");
            }
            commands.add(new LineCommand(sku, quantity));
        }
        return commands;
    }

    private static String describeLines(ObjectMapper mapper, String args) {
        StringBuilder sb = new StringBuilder("创建订单：");
        for (LineCommand command : parseLines(mapper, args)) {
            if (sb.charAt(sb.length() - 1) != '：') {
                sb.append('、');
            }
            sb.append(command.sku()).append(" × ").append(command.quantity());
        }
        return sb.toString();
    }

    /** One 商品 / 数量 pair per ordered line, so nothing about the request is hidden. */
    private static List<ToolField> lineFields(ObjectMapper mapper, String args) {
        List<ToolField> fields = new ArrayList<>();
        JsonNode lines = root(mapper, args).path("lines");
        if (!lines.isArray()) {
            return fields;
        }
        for (JsonNode line : lines) {
            String sku = line.path("sku").asText("");
            fields.add(new ToolField("商品", sku.isBlank() ? "—" : sku));
            fields.add(new ToolField("数量", String.valueOf(line.path("quantity").asInt(0))));
        }
        return fields;
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少参数: " + field);
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "工具参数不是合法 JSON");
        }
    }

    private static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "结果序列化失败");
        }
    }
}
