package com.example.ordermgt.assistant.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.ActionCardFactory;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.assistant.domain.PendingActionRepository;
import com.example.ordermgt.assistant.domain.RunRepository;
import com.example.ordermgt.assistant.domain.ToolRegistry;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Business rule: 归档的对外契约（ADR-0013）——静止的会话可归档并默认从列表消失、带 includeArchived 可见； 非静止（有 RUNNING Run
 * 或未过期卡片）→ 409；恢复回来；越权一律 403。
 */
class ArchiveContractTest {

    private static final Long CUSTOMER = 1001L;
    private static final Long OTHER = 2002L;
    private static final Long CONVERSATION = 7L;

    private ConversationRepository conversationRepository;
    private RunRepository runRepository;
    private PendingActionRepository pendingActionRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        runRepository = mock(RunRepository.class);
        pendingActionRepository = mock(PendingActionRepository.class);

        ConversationService conversationService =
                new ConversationService(
                        conversationRepository,
                        pendingActionRepository,
                        runRepository,
                        mock(AiInvocationRepository.class),
                        new ActionCardFactory(new ToolRegistry(List.of()), new ObjectMapper()));

        AssistantService assistantService = mock(AssistantService.class);
        ConversationRunQueue queue = new ConversationRunQueue(Runnable::run, 5);
        AssistantApiMapper mapper = Mappers.getMapper(AssistantApiMapper.class);
        ChatSender chatSender =
                new ChatSender(
                        assistantService,
                        new IdempotencyGuard(
                                mock(IdempotencyRepository.class), new ObjectMapper(), 24),
                        mapper,
                        conversationService,
                        queue,
                        mock(ConversationEventPublisher.class));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConversationController(
                                        conversationService,
                                        assistantService,
                                        chatSender,
                                        queue,
                                        new RunRegistry(),
                                        new RunService(runRepository, new RunRegistry()),
                                        mock(ConversationEventPublisher.class),
                                        mock(AiAssistantPort.class),
                                        new ObjectMapper(),
                                        mapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void archivingAQuiescentConversationAnswersOkWithArchivedStatus() throws Exception {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(CUSTOMER, ConversationStatus.ACTIVE)));
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.runningConversationIds(List.of(CONVERSATION))).thenReturn(Set.of());
        when(pendingActionRepository.livePendingConversationIds(any(), any())).thenReturn(Set.of());

        mvc.perform(
                        post("/api/assistant/conversations/{id}/archive", CONVERSATION)
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.state").value("IDLE"));
    }

    @Test
    void archivingAConversationWithARunInFlightIsConflict() throws Exception {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(CUSTOMER, ConversationStatus.ACTIVE)));
        when(runRepository.runningConversationIds(List.of(CONVERSATION)))
                .thenReturn(Set.of(CONVERSATION));

        mvc.perform(
                        post("/api/assistant/conversations/{id}/archive", CONVERSATION)
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void restoringAnArchivedConversationAnswersOkWithActiveStatus() throws Exception {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(CUSTOMER, ConversationStatus.ARCHIVED)));
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(
                        post("/api/assistant/conversations/{id}/restore", CONVERSATION)
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void archivingSomeoneElsesConversationIsForbidden() throws Exception {
        when(conversationRepository.findById(CONVERSATION))
                .thenReturn(Optional.of(conversation(OTHER, ConversationStatus.ACTIVE)));

        mvc.perform(
                        post("/api/assistant/conversations/{id}/archive", CONVERSATION)
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isForbidden());
    }

    @Test
    void theDefaultListHidesArchivedAndIncludeArchivedShowsThem() throws Exception {
        Conversation active = conversation(CUSTOMER, 7L, ConversationStatus.ACTIVE);
        Conversation archived = conversation(CUSTOMER, 8L, ConversationStatus.ARCHIVED);
        when(conversationRepository.findByCustomer(CUSTOMER, false)).thenReturn(List.of(active));
        when(conversationRepository.findByCustomer(CUSTOMER, true))
                .thenReturn(List.of(active, archived));

        mvc.perform(get("/api/assistant/conversations").header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(7));

        mvc.perform(
                        get("/api/assistant/conversations")
                                .header("X-Customer-Id", CUSTOMER)
                                .queryParam("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].status").value("ARCHIVED"));
    }

    private static Conversation conversation(Long customerId, ConversationStatus status) {
        return conversation(customerId, CONVERSATION, status);
    }

    private static Conversation conversation(Long customerId, Long id, ConversationStatus status) {
        Instant now = Instant.now();
        return Conversation.rehydrate(id, customerId, "title", status, now, now, now);
    }
}
