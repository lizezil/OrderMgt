package com.example.ordermgt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.AssistantService;
import com.example.ordermgt.assistant.application.ConversationService;
import com.example.ordermgt.assistant.application.MessageView;
import com.example.ordermgt.assistant.application.RunReconciler;
import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.AiInvocationOutcome;
import com.example.ordermgt.assistant.domain.AiInvocationRepository;
import com.example.ordermgt.assistant.domain.AiUpstreamException;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.infrastructure.ai.MockAiAdapter;
import com.example.ordermgt.assistant.infrastructure.persist.RunMapper;
import com.example.ordermgt.assistant.infrastructure.persist.RunPO;
import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.support.CleanSchemaConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Run is the execution unit, so it must be visible in the database, not just in memory (ADR-0011):
 * both send paths leave a RUNNING -> COMPLETED row, a failed model call leaves a FAILED row with
 * the reason, and the sidebar reads its state off those rows.
 *
 * <p>Against the real MySQL and a stubbed model, like {@code AssistantFlowTest}: the model is
 * offline (keyword-driven) so the suite spends no quota.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({CleanSchemaConfiguration.class, RunPersistenceTest.StubAiConfiguration.class})
class RunPersistenceTest {

    private static final Long CUSTOMER = 9100L;

    @TestConfiguration
    static class StubAiConfiguration {

        /** Every call for this customer fails, so the FAILED path can be exercised offline. */
        static final Long FAILING_CUSTOMER = 9199L;

        static final AiAssistantPort DELEGATE = new MockAiAdapter();

        @Bean
        @Primary
        AiAssistantPort stubAiAssistantPort() {
            return new AiAssistantPort() {

                @Override
                public AssistantTurn respond(
                        Long customerId, Long conversationId, List<ChatMessage> memory) {
                    failIfDoomed(customerId);
                    return DELEGATE.respond(customerId, conversationId, memory);
                }

                @Override
                public AssistantTurn respondStreaming(
                        Long customerId,
                        Long conversationId,
                        List<ChatMessage> memory,
                        Consumer<String> onTextDelta,
                        RunCancellation cancellation) {
                    failIfDoomed(customerId);
                    return DELEGATE.respondStreaming(
                            customerId, conversationId, memory, onTextDelta, cancellation);
                }

                private void failIfDoomed(Long customerId) {
                    if (FAILING_CUSTOMER.equals(customerId)) {
                        throw new AiUpstreamException(
                                ErrorCode.UPSTREAM_AI_ERROR,
                                "AI upstream call failed",
                                new RuntimeException("provider down"),
                                "offline-mock",
                                12,
                                AiInvocationOutcome.ERROR);
                    }
                }
            };
        }
    }

    @Autowired private AssistantService assistantService;

    @Autowired private ConversationService conversationService;

    @Autowired private AiInvocationRepository aiInvocationRepository;

    @Autowired private AiAssistantPort aiAssistantPort;

    @Autowired private RunReconciler runReconciler;

    @Autowired private RunMapper runMapper;

    @Autowired private MockMvc mockMvc;

    @Test
    void aSynchronousTurnLeavesOneCompletedRun() {
        AssistantResult result = assistantService.chat(CUSTOMER, null, "help me order 3 GIZMO-03");

        List<RunPO> runs = runsOf(result.conversationId());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(runs.get(0).getStartedAt()).isNotNull();
        assertThat(runs.get(0).getEndedAt()).isNotNull();
        assertThat(runs.get(0).getError()).isNull();
    }

    /** The streaming transport differs only in how the model is called: same Run, same closure. */
    @Test
    void aStreamingTurnLeavesOneCompletedRun() {
        Long customer = CUSTOMER + 1;
        AssistantService.TurnContext context =
                assistantService.prepare(customer, null, "help me order 3 GIZMO-03");
        List<String> deltas = new ArrayList<>();

        assistantService.executeTurn(
                context,
                () ->
                        aiAssistantPort.respondStreaming(
                                customer,
                                context.conversation().getId(),
                                context.memory(),
                                deltas::add,
                                RunCancellation.NEVER));

        List<RunPO> runs = runsOf(context.conversation().getId());
        // 断线只停投递（这里表现为 delta 不再有人听），**不**产生 CANCELLED：只留下一条 COMPLETED，
        // 审计记 OK。停止是用户的显式动作，不是断线的副作用（ADR-0009 / ADR-0012）。
        assertThat(runs).extracting(RunPO::getStatus).containsExactly("COMPLETED");
        assertThat(runs.get(0).getEndedAt()).isNotNull();
        assertThat(aiInvocationRepository.findByConversationId(context.conversation().getId()))
                .extracting(AiInvocation::outcome)
                .containsExactly(AiInvocationOutcome.OK);
    }

    @Test
    void aFailedModelCallLeavesAFailedRunWithTheReason() {
        Long customer = StubAiConfiguration.FAILING_CUSTOMER;
        AssistantService.TurnContext context = assistantService.prepare(customer, null, "hello");

        assertThatThrownBy(
                        () ->
                                assistantService.executeTurn(
                                        context,
                                        () ->
                                                aiAssistantPort.respond(
                                                        customer,
                                                        context.conversation().getId(),
                                                        context.memory())))
                .isInstanceOf(AiUpstreamException.class);

        List<RunPO> runs = runsOf(context.conversation().getId());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(runs.get(0).getError()).isNotNull();
        assertThat(runs.get(0).getEndedAt()).isNotNull();
    }

    /**
     * 客户停止了一个流式回合（ADR-0012）：Run 收 CANCELLED；已经收到的半截文本作为一条**普通的 ASSISTANT 消息**落库（不写 Message 状态，守
     * ADR-0009）；审计记一行 CANCELLED。这里直接驱动 executeTurn the cancel branch of executeTurn (the upstream
     * abort lives in SpringAiAdapter, pinned by its own unit tests); what is verified here is the
     * persistence side.
     */
    @Test
    void aStoppedStreamingTurnKeepsTheHalfTextAndClosesTheRunCancelled() {
        Long customer = CUSTOMER + 30;
        AssistantService.TurnContext context =
                assistantService.prepare(customer, null, "帮我下单 3 个 GIZMO-03");
        AssistantTurn partial = AssistantTurn.of("我先说半句，", List.of(), AiUsage.EMPTY);

        assertThatThrownBy(
                        () ->
                                assistantService.executeTurn(
                                        context,
                                        run -> {},
                                        () -> {
                                            throw new RunCancelledException(partial);
                                        }))
                .isInstanceOf(RunCancelledException.class);

        List<RunPO> runs = runsOf(context.conversation().getId());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("CANCELLED");
        assertThat(runs.get(0).getEndedAt()).isNotNull();

        List<MessageView> history =
                conversationService.history(customer, context.conversation().getId());
        MessageView last = history.get(history.size() - 1);
        assertThat(last.message().role()).isEqualTo(Role.ASSISTANT);
        assertThat(last.message().content()).isEqualTo("我先说半句，");

        List<AiInvocation> audits =
                aiInvocationRepository.findByConversationId(context.conversation().getId());
        assertThat(audits)
                .extracting(AiInvocation::outcome)
                .containsExactly(AiInvocationOutcome.CANCELLED);
    }

    @Test
    void theConversationListCarriesStatusAndDerivedState() throws Exception {
        Long customer = CUSTOMER + 10;
        Long conversationId =
                assistantService.chat(customer, null, "help me order 3 GIZMO-03").conversationId();

        // The turn finished (Run COMPLETED) but its createOrder card is still live ->
        // WAITING_APPROVAL.
        listFor(customer)
                .andExpect(jsonPath("$.data[0].id").value(conversationId))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].state").value("WAITING_APPROVAL"));

        // A RUNNING run wins over the card.
        runMapper.insert(runningRun(conversationId));
        listFor(customer).andExpect(jsonPath("$.data[0].state").value("RUNNING"));
    }

    private org.springframework.test.web.servlet.ResultActions listFor(Long customer)
            throws Exception {
        return mockMvc.perform(
                        get("/api/assistant/conversations").with(user(String.valueOf(customer))))
                .andExpect(status().isOk());
    }

    /**
     * 重启对账：启动那一刻不可能有活 Run，所以残留的 RUNNING 一定是上一个进程留下的；启动钩子一次性把它们 标成 INTERRUPTED 并补上 ended_at，让状态列说真话。
     *
     * <p>这里驱动的是 {@link RunReconciler} 真正的启动入口（{@code ApplicationRunner.run}），正是 Spring
     * 启动时会调的那一下；「一次性、不是定时器」由 L2 的 {@code RunReconcilerTest} 钉住。
     */
    @Test
    void aRunLeftRunningByAPreviousProcessIsInterruptedOnStartup() {
        Long conversationId =
                assistantService
                        .chat(CUSTOMER + 20, null, "help me order 3 GIZMO-03")
                        .conversationId();
        runMapper.insert(runningRun(conversationId));
        // 别的方法可能也留了 RUNNING 行（一个类只清一次 schema），所以断言「全部收尾」而非只看这一条。
        assertThat(runningRuns()).isGreaterThanOrEqualTo(1);

        runReconciler.run(new DefaultApplicationArguments());

        assertThat(runningRuns()).isZero();
        RunPO reconciled = interruptedRunOf(conversationId);
        assertThat(reconciled.getEndedAt()).isNotNull();
        assertThat(reconciled.getError()).isEqualTo(RunReconciler.INTERRUPTED_REASON);
    }

    /**
     * 被中断的回合只能「重发一条全新的 Run」（ADR-0009 已否决断点续传）：重试不改写那条 INTERRUPTED， 而是把最后一条客户消息重新走一遍 prepare → 模型 →
     * 落库，留下一行新的。
     */
    @Test
    void retryingOpensANewRunAndLeavesTheInterruptedOneAlone() {
        Long customer = CUSTOMER + 21;
        Long conversationId =
                assistantService.chat(customer, null, "help me order 3 GIZMO-03").conversationId();
        // 跑到一半就没了的回合：客户消息已在 prepare 里落库，回复没有。
        runMapper.insert(runningRun(conversationId));
        runReconciler.run(new DefaultApplicationArguments());

        RunPO interrupted = interruptedRunOf(conversationId);
        Long interruptedId = interrupted.getId();
        Instant interruptedEnd = interrupted.getEndedAt();

        // 重试 = 把最后一条客户消息作为全新的一轮重发。
        assistantService.chat(customer, conversationId, "help me order 3 GIZMO-03");

        RunPO untouched = runMapper.selectById(interruptedId);
        assertThat(untouched.getStatus()).isEqualTo("INTERRUPTED");
        assertThat(untouched.getEndedAt()).isEqualTo(interruptedEnd);
        assertThat(runsOf(conversationId))
                .extracting(RunPO::getStatus)
                .containsExactlyInAnyOrder("COMPLETED", "INTERRUPTED", "COMPLETED");
    }

    private long runningRuns() {
        return runMapper.selectCount(Wrappers.<RunPO>lambdaQuery().eq(RunPO::getStatus, "RUNNING"));
    }

    private RunPO interruptedRunOf(Long conversationId) {
        return runsOf(conversationId).stream()
                .filter(run -> "INTERRUPTED".equals(run.getStatus()))
                .findFirst()
                .orElseThrow();
    }

    private List<RunPO> runsOf(Long conversationId) {
        return runMapper.selectList(
                Wrappers.<RunPO>lambdaQuery().eq(RunPO::getConversationId, conversationId));
    }

    private static RunPO runningRun(Long conversationId) {
        RunPO po = new RunPO();
        po.setConversationId(conversationId);
        po.setStatus("RUNNING");
        po.setStartedAt(Instant.now());
        return po;
    }
}
