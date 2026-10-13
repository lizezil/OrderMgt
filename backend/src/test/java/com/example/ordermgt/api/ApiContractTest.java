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
import com.example.ordermgt.assistant.api.ChatSender;
import com.example.ordermgt.assistant.api.ConversationController;
import com.example.ordermgt.assistant.application.ActionConfirmationService;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocation;
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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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

    private ConversationService conversationService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        idempotencyRepository = mock(IdempotencyRepository.class);
        orderService = mock(OrderService.class);
        actionConfirmationService = mock(ActionConfirmationService.class);
        catalogRepository = mock(CatalogRepository.class);
        assistantService = mock(AssistantService.class);
        conversationService = mock(ConversationService.class);

        IdempotencyGuard guard =
                new IdempotencyGuard(idempotencyRepository, new ObjectMapper(), 24);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // Standalone MockMvc defaults to epoch-second dates; the running app writes ISO-8601
        // (Jackson2ObjectMapperBuilder disables WRITE_DATES_AS_TIMESTAMPS). The contract must be
        // pinned in the format customers actually receive.
        ObjectMapper wire =
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build();

        AssistantApiMapper assistantMapper = Mappers.getMapper(AssistantApiMapper.class);

        // Direct execution: this suite is about the contract on the wire, not about scheduling, so
        // an admitted turn runs on the calling thread.
        ConversationRunQueue runQueue = new ConversationRunQueue(Runnable::run, 5);
        ChatSender chatSender =
                new ChatSender(
                        assistantService, guard, assistantMapper, conversationService, runQueue);

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
                                new AssistantController(chatSender),
                                new ConversationController(
                                        conversationService,
                                        assistantService,
                                        chatSender,
                                        runQueue,
                                        new RunRegistry(),
                                        mock(RunService.class),
                                        mock(AiAssistantPort.class),
                                        new ObjectMapper(),
                                        assistantMapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setValidator(validator)
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(wire))
                        .build();
    }

    @Test
    void missingCustomerHeaderReturnsValidationError() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(
                        jsonPath("$.message")
                                .value(org.hamcrest.Matchers.containsString("X-Customer-Id")));
    }

    @Test
    void orderWithoutLinesReturnsValidationError() throws Exception {
        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonPositiveQuantityReturnsValidationError() throws Exception {
        mvc.perform(
                        post("/api/orders")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"lines\":[{\"sku\":\"WIDGET-01\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void successfulOrderReturnsUnifiedSuccessBody() throws Exception {
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
    void retryWithSameIdempotencyKeyReplaysResponseWithoutCreatingOrder() throws Exception {
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
    void reusingIdempotencyKeyForDifferentRequestReturnsConflict() throws Exception {
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
    void catalogExposesOnlySkuNameAndUnitPrice() throws Exception {
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
    void confirmingAnotherCustomersCardReturnsForbidden() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(actionConfirmationService)
                .confirm(CUSTOMER, "tok");

        mvc.perform(post("/api/assistant/actions/tok/confirm").header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void confirmingMissingCardReturnsNotFound() throws Exception {
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
    void confirmingExpiredCardReturnsGone() throws Exception {
        doThrow(new BusinessException(ErrorCode.ACTION_EXPIRED))
                .when(actionConfirmationService)
                .confirm(CUSTOMER, "old");

        mvc.perform(post("/api/assistant/actions/old/confirm").header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACTION_EXPIRED"));
    }

    @Test
    void emptyAssistantMessageReturnsValidationError() throws Exception {
        mvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void normalAssistantReplyCarriesModelAndLatency() throws Exception {
        when(assistantService.chat(eq(CUSTOMER), isNull(), eq("hello")))
                .thenReturn(
                        new AssistantResult(
                                5L,
                                "hello",
                                List.of(),
                                List.of(),
                                new AiUsage(1, 2),
                                "qwen-max",
                                1234));

        mvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(5))
                .andExpect(jsonPath("$.data.reply").value("hello"))
                .andExpect(jsonPath("$.data.model").value("qwen-max"))
                .andExpect(jsonPath("$.data.latencyMs").value(1234));
    }

    @Test
    void upstreamModelFailureReturnsBadGatewayNotInternalError() throws Exception {
        when(assistantService.chat(anyLong(), any(), anyString()))
                .thenThrow(
                        new AiUpstreamException(
                                ErrorCode.UPSTREAM_AI_ERROR,
                                "AI upstream call failed",
                                new RuntimeException("boom"),
                                "qwen-max",
                                10,
                                AiInvocationOutcome.TIMEOUT));

        mvc.perform(
                        post("/api/assistant/chat")
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_AI_ERROR"));
    }

    @Test
    void conversationAuditExposesModelLatencyOutcomeAndWhen() throws Exception {
        when(conversationService.invocations(CUSTOMER, 7L))
                .thenReturn(
                        List.of(
                                new AiInvocation(
                                        11L,
                                        7L,
                                        CUSTOMER,
                                        "qwen-max",
                                        12,
                                        34,
                                        4242,
                                        AiInvocationOutcome.TIMEOUT,
                                        "read timed out",
                                        null,
                                        Instant.parse("2026-10-13T05:04:05Z"))));

        mvc.perform(
                        get("/api/assistant/conversations/7/invocations")
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].model").value("qwen-max"))
                .andExpect(jsonPath("$.data[0].latencyMs").value(4242))
                .andExpect(jsonPath("$.data[0].outcome").value("TIMEOUT"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-13T05:04:05Z"));
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
