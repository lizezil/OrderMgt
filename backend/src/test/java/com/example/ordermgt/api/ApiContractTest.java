package com.example.ordermgt.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.api.ActionController;
import com.example.ordermgt.assistant.api.AssistantApiMapper;
import com.example.ordermgt.assistant.api.AssistantController;
import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.catalog.api.CatalogApiMapper;
import com.example.ordermgt.catalog.api.CatalogController;
import com.example.ordermgt.catalog.domain.CatalogItem;
import com.example.ordermgt.catalog.domain.CatalogRepository;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRecord;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.idempotency.domain.IdempotencyStatus;
import com.example.ordermgt.order.api.OrderApiMapper;
import com.example.ordermgt.order.api.OrderController;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.order.domain.OrderLine;
import com.example.ordermgt.order.domain.OrderNumber;
import com.example.ordermgt.order.domain.OrderStatus;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Business rule: 对外契约——统一响应体、错误码到 HTTP 状态的映射、参数与身份校验。
 *
 * <p>客户和前端只通过这一层认识系统：成功必须是 {@code {code:"OK",…}}，失败必须是一个能照做的 状态码（400/403/404/409/410/502），而不是一个笼统的
 * 500。这一层不碰数据库，纯粹验契约。
 */
class ApiContractTest {

    private static final Long CUSTOMER = 1001L;

    private IdempotencyRepository idempotencyRepository;

    private OrderService orderService;

    private ActionConfirmationService actionConfirmationService;

    private CatalogRepository catalogRepository;

    private AssistantService assistantService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        idempotencyRepository = mock(IdempotencyRepository.class);
        orderService = mock(OrderService.class);
        actionConfirmationService = mock(ActionConfirmationService.class);
        catalogRepository = mock(CatalogRepository.class);
        assistantService = mock(AssistantService.class);

        IdempotencyGuard guard =
                new IdempotencyGuard(idempotencyRepository, new ObjectMapper(), 24);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OrderController(
                                        orderService,
                                        Mappers.getMapper(OrderApiMapper.class),
                                        guard),
                                new CatalogController(
                                        catalogRepository,
                                        Mappers.getMapper(CatalogApiMapper.class)),
                                new ActionController(actionConfirmationService),
                                new AssistantController(
                                        assistantService,
                                        guard,
                                        Mappers.getMapper(AssistantApiMapper.class)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setValidator(validator)
                        .build();
    }

    @Test
    void 缺少客户身份头时返回参数错误() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(
                        jsonPath("$.message")
                                .value(org.hamcrest.Matchers.containsString("X-Customer-Id")));
    }

    @Test
    void 下单请求没有商品行时返回参数错误() throws Exception {
        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 下单数量非正时返回参数错误() throws Exception {
        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 下单成功返回统一的成功响应体() throws Exception {
        when(orderService.createOrder(eq(CUSTOMER), any())).thenReturn(order("ORD-1"));

        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-1"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.totalAmount").value(19.90));
    }

    @Test
    void 重试同一幂等键会重放原响应而不再下单() throws Exception {
        when(idempotencyRepository.tryClaim(any())).thenReturn(Optional.empty());
        when(idempotencyRepository.find(CUSTOMER, "CREATE_ORDER", "k2"))
                .thenReturn(
                        Optional.of(
                                record(
                                        "k2",
                                        IdempotencyGuard.hashOf("WIDGET-01:2"),
                                        "{\"orderNo\":\"ORD-REPLAY\",\"status\":\"CREATED\",\"totalAmount\":19.90,\"lines\":[],\"createdAt\":null,\"updatedAt\":null}")));

        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .header("Idempotency-Key", "k2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderNo").value("ORD-REPLAY"));

        verify(orderService, never()).createOrder(any(), any());
    }

    @Test
    void 同一幂等键被换成另一个请求时返回冲突() throws Exception {
        when(idempotencyRepository.tryClaim(any())).thenReturn(Optional.empty());
        when(idempotencyRepository.find(CUSTOMER, "CREATE_ORDER", "k3"))
                .thenReturn(Optional.of(record("k3", "a-different-hash", "{}")));

        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .header("Idempotency-Key", "k3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void 目录只暴露商品编号名称与单价() throws Exception {
        when(catalogRepository.findAllActive())
                .thenReturn(
                        List.of(
                                new CatalogItem(
                                        1L, "WIDGET-01", "Widget", new BigDecimal("19.90"), true)));

        mvc.perform(get("/api/catalog/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sku").value("WIDGET-01"))
                .andExpect(jsonPath("$.data[0].name").value("Widget"))
                .andExpect(jsonPath("$.data[0].unitPrice").value(19.90))
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].active").doesNotExist());
    }

    @Test
    void 确认他人的卡片返回无权() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(actionConfirmationService)
                .confirm(CUSTOMER, "tok");

        mvc.perform(post("/api/assistant/actions/tok/confirm").header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 确认不存在的卡片返回未找到() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_FOUND))
                .when(actionConfirmationService)
                .confirm(CUSTOMER, "deadbeef");

        mvc.perform(
                        post("/api/assistant/actions/deadbeef/confirm")
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 确认已过期的卡片返回已失效() throws Exception {
        doThrow(new BusinessException(ErrorCode.ACTION_EXPIRED))
                .when(actionConfirmationService)
                .confirm(CUSTOMER, "old");

        mvc.perform(post("/api/assistant/actions/old/confirm").header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACTION_EXPIRED"));
    }

    @Test
    void 助手收到空消息时返回参数错误() throws Exception {
        mvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 助手正常回复带回模型与耗时() throws Exception {
        when(assistantService.chat(eq(CUSTOMER), isNull(), eq("你好")))
                .thenReturn(
                        new AssistantResult(
                                5L,
                                "你好",
                                List.of(),
                                List.of(),
                                new AiUsage(1, 2),
                                "qwen-max",
                                1234));

        mvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(5))
                .andExpect(jsonPath("$.data.reply").value("你好"))
                .andExpect(jsonPath("$.data.model").value("qwen-max"))
                .andExpect(jsonPath("$.data.latencyMs").value(1234));
    }

    @Test
    void 模型上游失败时返回网关错误而不是服务内部错误() throws Exception {
        when(assistantService.chat(anyLong(), any(), anyString()))
                .thenThrow(
                        new AiUpstreamException(
                                ErrorCode.UPSTREAM_AI_ERROR,
                                "AI 服务调用失败",
                                new RuntimeException("boom"),
                                "qwen-max",
                                10,
                                AiInvocationOutcome.TIMEOUT));

        mvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_AI_ERROR"));
    }

    private static Order order(String orderNumber) {
        Instant now = Instant.now();
        return Order.rehydrate(
                null,
                new OrderNumber(orderNumber),
                CUSTOMER,
                OrderStatus.CREATED,
                List.of(OrderLine.of("WIDGET-01", "Widget", 1, new BigDecimal("19.90"))),
                now,
                now,
                0);
    }

    private static IdempotencyRecord record(String key, String requestHash, String responseJson) {
        Instant now = Instant.now();
        return new IdempotencyRecord(
                9L,
                CUSTOMER,
                "CREATE_ORDER",
                key,
                requestHash,
                IdempotencyStatus.COMPLETED,
                null,
                responseJson,
                now,
                now,
                now.plusSeconds(3600));
    }
}
