package com.example.ordermgt.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationRunQueue;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.RunRegistry;
import com.example.ordermgt.assistant.application.RunService;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.idempotency.domain.IdempotencyRepository;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import com.example.ordermgt.shared.error.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Business rule: 忙时两种入口给出两种答案——流式入队（200 + 一条带 id 的排队事件），同步入队不了就 409； 队伍满了是 429 QUEUE_FULL。
 *
 * <p>这里只验对外契约。入队本身不碰模型、不落库（没有 Run 行可留），所以"排队"在契约上就是"这条流被接受、 并被告知它在队伍里的位置与可删除的
 * id"；身份校验必须发生在占位之前，否则外人能靠 409/排队事件认出别人某个 会话正忙。
 */
class QueueContractTest {

    private static final Long CUSTOMER = 1001L;
    private static final Long CONVERSATION = 7L;

    private ConversationService conversationService;
    private AssistantService assistantService;
    private ConversationRunQueue runQueue;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        assistantService = mock(AssistantService.class);
        // Nothing is executed: these tests are about admission, so a turn that gets dispatched just
        // stays "running" and never releases its slot.
        runQueue = new ConversationRunQueue(command -> {}, 5);
        mvc = mvcOver(runQueue);
    }

    /** Builds the controller over a given queue, so a test can choose how dispatched turns run. */
    private MockMvc mvcOver(ConversationRunQueue queue) {
        AssistantApiMapper mapper = Mappers.getMapper(AssistantApiMapper.class);
        ChatSender chatSender =
                new ChatSender(
                        assistantService,
                        new IdempotencyGuard(
                                mock(IdempotencyRepository.class), new ObjectMapper(), 24),
                        mapper,
                        conversationService,
                        queue);

        return MockMvcBuilders.standaloneSetup(
                        new ConversationController(
                                conversationService,
                                assistantService,
                                chatSender,
                                queue,
                                new RunRegistry(),
                                mock(RunService.class),
                                mock(AiAssistantPort.class),
                                new ObjectMapper(),
                                mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aStreamingTurnArrivingOnABusyConversationWaitsInsteadOfFailing() throws Exception {
        occupyConversation();

        MvcResult result = stream("第二条").andExpect(status().isOk()).andReturn();

        // 排队事件就是"推给前端"的那一条：没有它，前端看得见队伍却删不掉任何一项。
        String frame = result.getResponse().getContentAsString();
        assertThat(frame).contains("event:queued");
        assertThat(frame).contains("\"itemId\":\"q1\"").contains("\"position\":1");
    }

    @Test
    void aQueuedTurnIsTakenOffTheLineThroughItsId() throws Exception {
        occupyConversation();
        String itemId = queuedItemId();

        mvc.perform(
                        delete(
                                        "/api/assistant/conversations/{id}/queue/{itemId}",
                                        CONVERSATION,
                                        itemId)
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // Idempotent in effect: the item is gone, so the same delete is a 404.
        mvc.perform(
                        delete(
                                        "/api/assistant/conversations/{id}/queue/{itemId}",
                                        CONVERSATION,
                                        itemId)
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aQueueItemThatWasNeverWaitingIsNotFound() throws Exception {
        occupyConversation();

        mvc.perform(
                        delete(
                                        "/api/assistant/conversations/{id}/queue/{itemId}",
                                        CONVERSATION,
                                        "q-does-not-exist")
                                .header("X-Customer-Id", CUSTOMER))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTurnLeavingTheLineSaysSoBeforeItStartsAnswering() throws Exception {
        // Running inline here, so the turn really starts: the frame that says "out of the line" is
        // what lets a client stop counting a running turn as waiting, before any token arrives.
        MockMvc inline = mvcOver(new ConversationRunQueue(Runnable::run, 5));

        MvcResult result = inline.perform(request("马上开始")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("event:started");
    }

    @Test
    void theSixthWaitingTurnIsRefusedWithQueueFull() throws Exception {
        occupyConversation();
        for (int i = 0; i < 5; i++) {
            runQueue.submitStreaming(CONVERSATION, noopTurn());
        }

        stream("第六条")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUEUE_FULL"));
    }

    @Test
    void aBlockingSendWhileAStreamRunsIsRefusedWithConflict() throws Exception {
        occupyConversation();

        mvc.perform(
                        post("/api/assistant/conversations/{id}/messages", CONVERSATION)
                                .header("X-Customer-Id", CUSTOMER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"帮我下单\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Refused means not run: no model call, hence no Run row for a turn that never happened.
        verify(assistantService, never()).chat(any(), any(), any());
    }

    @Test
    void queueingOpensNoRun() throws Exception {
        occupyConversation();

        stream("只会排队").andExpect(status().isOk());

        // A waiting item has no row of its own; the turn it will become opens its Run only when it
        // reaches the front, so nothing here may have started a turn.
        verify(assistantService, never()).chat(any(), any(), any());
        verify(assistantService, never()).executeTurn(any(), any());
        verify(assistantService, never()).prepare(any(), any(), any());
    }

    @Test
    void aStrangersStreamIsRefusedBeforeItCanTakeAQueueSlot() throws Exception {
        when(conversationService.getOwned(CUSTOMER, CONVERSATION))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "forbidden"));

        stream("偷看的").andExpect(status().isForbidden());

        // Nothing was admitted, so a stranger cannot even discover that this conversation is busy.
        assertThat(runQueue.isBusy(CONVERSATION)).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions stream(String message)
            throws Exception {
        return mvc.perform(request(message));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String message) {
        return get("/api/assistant/conversations/{id}/stream", CONVERSATION)
                .header("X-Customer-Id", CUSTOMER)
                .queryParam("message", message);
    }

    /** Puts a turn in flight so the next arrival has to wait. */
    private void occupyConversation() {
        assertThat(runQueue.submitStreaming(CONVERSATION, noopTurn()))
                .isInstanceOf(ConversationRunQueue.Admission.Started.class);
    }

    private String queuedItemId() {
        ConversationRunQueue.Admission.Queued queued =
                (ConversationRunQueue.Admission.Queued)
                        runQueue.submitStreaming(CONVERSATION, noopTurn());
        return queued.itemId();
    }

    private static ConversationRunQueue.Turn noopTurn() {
        return new ConversationRunQueue.Turn() {

            @Override
            public void start() {}

            @Override
            public void drop() {}
        };
    }
}
