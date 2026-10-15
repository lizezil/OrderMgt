package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.Role;
import com.example.ordermgt.assistant.domain.RunCancellation;
import com.example.ordermgt.assistant.domain.RunCancelledException;
import com.example.ordermgt.assistant.infrastructure.ai.prompt.OfflinePrompts;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * An offline stand-in for the model, so the assistant can be demonstrated end to end without a
 * DashScope key.
 *
 * <p>{@code @Profile("offline")}: the real {@link SpringAiAdapter} is {@code @Profile("!offline")},
 * so exactly one port implementation is ever a bean. Run it with {@code
 * SPRING_PROFILES_ACTIVE=mysql,offline} -- the {@code mysql} half is what supplies the datasource.
 *
 * <p>It is a keyword-driven fake, not a model: it reads the last customer message and proposes a
 * whitelisted tool call the same way the real adapter does, so the confirm-then-execute loop is
 * exercised for real, only the "thinking" is canned. The integration test reuses this class for the
 * same reason.
 */
@Slf4j
@Component
@Profile("offline")
public class MockAiAdapter implements AiAssistantPort {

    /** "2 DOODAD-04" -- and "2 个 DOODAD-04", since the demo is driven in Chinese too. */
    private static final Pattern COUNT_THEN_SKU =
            Pattern.compile("(\\d+)\\s*(?:[\\u4e00-\\u9fa5]{1,2}|x\\s+)?\\s*([A-Za-z]+-\\d+)");

    /** "DOODAD-04 x 3". */
    private static final Pattern SKU_THEN_COUNT =
            Pattern.compile("([A-Za-z]+-\\d+)\\s*[xX×]\\s*(\\d+)");

    private static final Pattern ORDER_NUMBER = Pattern.compile("ORD[0-9A-Z]+");

    /** How many characters of the canned reply arrive per streamed delta. */
    private static final int CHUNK_SIZE = 6;

    @Override
    public AssistantTurn respond(Long customerId, Long conversationId, List<ChatMessage> memory) {
        long start = System.nanoTime();
        AssistantTurn canned = turn(lastCustomerMessage(memory));
        return new AssistantTurn(
                canned.text(),
                canned.toolCalls(),
                canned.usage(),
                OfflinePrompts.MODEL,
                elapsedMs(start));
    }

    @Override
    public AssistantTurn respondStreaming(
            Long customerId,
            Long conversationId,
            List<ChatMessage> memory,
            Consumer<String> onTextDelta,
            RunCancellation cancellation) {
        AssistantTurn turn = respond(customerId, conversationId, memory);
        // Real deltas, so the SPA's incremental rendering is exercised, not just faked. The stop
        // signal is polled between chunks exactly as the real adapter polls it, so cancellation is
        // reachable offline too.
        StringBuilder seen = new StringBuilder();
        for (String chunk : chunks(turn.text(), CHUNK_SIZE)) {
            if (cancellation.isCancelled()) {
                throw new RunCancelledException(partial(turn, seen.toString()));
            }
            onTextDelta.accept(chunk);
            seen.append(chunk);
        }
        return turn;
    }

    /** The half turn a stop leaves behind: the prose seen so far, none of the audited facts. */
    private static AssistantTurn partial(AssistantTurn turn, String text) {
        return new AssistantTurn(text, List.of(), turn.usage(), turn.model(), turn.latencyMs());
    }

    /**
     * The keyword table, in both languages the demo is driven in: the canned replies are Chinese
     * ({@link OfflinePrompts}), and the README's offline walkthrough types Chinese sentences, so a
     * stub that only listened for English would answer the documented prompts with "I don't know".
     * The Chinese words are picked to stay disjoint -- 「订单」 is a lookup, 「下单」 is an order.
     */
    private AssistantTurn turn(String message) {
        // 能力边界（US-1.10）：退款、改地址、开发票这类请求这里根本没有对应能力，如实说做不了。
        // 这一支必须排在最前面：「帮我退款订单 ORD…」里带着「订单」两个字，落到列表支就会答非所问；
        // 真实模型那边由 SYSTEM 提示词的同一句话负责，这里只是把同一份事实做成桩的台词。
        if (message.contains("退款")
                || message.contains("refund")
                || message.contains("发票")
                || message.contains("invoice")
                || message.contains("地址")
                || message.contains("address")) {
            return AssistantTurn.of(OfflinePrompts.OUT_OF_CAPABILITY, List.of(), AiUsage.EMPTY);
        }

        if (message.contains("cancel") || message.contains("取消")) {
            Matcher orderNumber = ORDER_NUMBER.matcher(message);
            if (orderNumber.find()) {
                String found = orderNumber.group();
                return AssistantTurn.of(
                        OfflinePrompts.cancelProposal(found),
                        List.of(
                                new ProposedToolCall(
                                        "cancelOrder",
                                        "{\"orderNumber\":\"" + found + "\"}",
                                        true)),
                        AiUsage.EMPTY);
            }
        }

        // 「最近一次下单」要的是一单，不是一页。这一支必须排在通用的列表支前面：否则顾客点名要最近一张、
        // 拿回的是最近二十张，而这一句正是顾客会打的那一句。
        if (message.contains("最近")
                || message.contains("最新")
                || message.contains("刚刚")
                || message.contains("latest")
                || message.contains("most recent")
                || message.contains("last order")) {
            return AssistantTurn.of(
                    OfflinePrompts.LOOKING_UP_LATEST,
                    List.of(new ProposedToolCall("queryMyOrders", "{\"limit\":1}", false)),
                    AiUsage.EMPTY);
        }

        if (message.contains("list")
                || message.contains("what")
                || message.contains("how many")
                || message.contains("check")
                || message.contains("query")
                || message.contains("show")
                || message.contains("订单")
                || message.contains("查一下")) {
            return AssistantTurn.of(
                    OfflinePrompts.LOOKING_UP_ORDERS,
                    List.of(new ProposedToolCall("queryMyOrders", "{}", false)),
                    AiUsage.EMPTY);
        }

        if (message.contains("buy")
                || message.contains("purchase")
                || message.contains("order")
                || message.contains("下单")
                || message.contains("购买")) {
            String lines = linesJson(message);
            if (lines != null) {
                return AssistantTurn.of(
                        OfflinePrompts.ORDER_PREPARED,
                        List.of(
                                new ProposedToolCall(
                                        "createOrder", "{\"lines\":[" + lines + "]}", true)),
                        AiUsage.EMPTY);
            }
            return AssistantTurn.of(OfflinePrompts.ASK_FOR_SKU, List.of(), AiUsage.EMPTY);
        }

        return AssistantTurn.of(OfflinePrompts.UNKNOWN_REQUEST, List.of(), AiUsage.EMPTY);
    }

    /**
     * Collects every "N SKU" / "SKU x N" pair in the message into the shape the createOrder tool
     * expects. Returns null when the message names no product, so the caller can ask instead of
     * inventing an order.
     */
    private static String linesJson(String message) {
        List<String> lines = new ArrayList<>();
        collect(COUNT_THEN_SKU, message, lines, 2, 1);
        collect(SKU_THEN_COUNT, message, lines, 1, 2);
        if (lines.isEmpty()) {
            return null;
        }
        return String.join(",", lines);
    }

    private static void collect(
            Pattern pattern, String message, List<String> lines, int skuGroup, int countGroup) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            String sku = matcher.group(skuGroup).toUpperCase();
            int quantity = Integer.parseInt(matcher.group(countGroup));
            lines.add("{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}");
        }
    }

    private static List<String> chunks(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** The newest thing the customer said -- not the newest message, which may be a tool result. */
    private static String lastCustomerMessage(List<ChatMessage> memory) {
        for (int i = memory.size() - 1; i >= 0; i--) {
            if (memory.get(i).role() == Role.CUSTOMER) {
                String content = memory.get(i).content();
                return content == null ? "" : content;
            }
        }
        return "";
    }
}
