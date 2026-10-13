package com.example.ordermgt.assistant.infrastructure.ai;

import com.example.ordermgt.assistant.domain.AiAssistantPort;
import com.example.ordermgt.assistant.domain.AiUsage;
import com.example.ordermgt.assistant.domain.AssistantTurn;
import com.example.ordermgt.assistant.domain.ChatMessage;
import com.example.ordermgt.assistant.domain.ProposedToolCall;
import com.example.ordermgt.assistant.domain.Role;
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

    /** "2 DOODAD-04". */
    private static final Pattern COUNT_THEN_SKU =
            Pattern.compile("(\\d+)\\s*(?:x\\s+)?([A-Za-z]+-\\d+)");

    /** "DOODAD-04 x 3". */
    private static final Pattern SKU_THEN_COUNT =
            Pattern.compile("([A-Za-z]+-\\d+)\\s*[xX×]\\s*(\\d+)");

    private static final Pattern ORDER_NUMBER = Pattern.compile("ORD[0-9A-Z]+");

    /** How many characters of the canned reply arrive per streamed delta. */
    private static final int CHUNK_SIZE = 6;

    @Override
    public AssistantTurn respond(Long customerId, List<ChatMessage> memory) {
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
            Long customerId, List<ChatMessage> memory, Consumer<String> onTextDelta) {
        AssistantTurn turn = respond(customerId, memory);
        // Real deltas, so the SPA's incremental rendering is exercised, not just faked.
        for (String chunk : chunks(turn.text(), CHUNK_SIZE)) {
            onTextDelta.accept(chunk);
        }
        return turn;
    }

    private AssistantTurn turn(String message) {
        if (message.contains("cancel")) {
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

        if (message.contains("list")
                || message.contains("what")
                || message.contains("how many")
                || message.contains("check")
                || message.contains("query")
                || message.contains("show")) {
            return AssistantTurn.of(
                    OfflinePrompts.LOOKING_UP_ORDERS,
                    List.of(new ProposedToolCall("queryMyOrders", "{}", false)),
                    AiUsage.EMPTY);
        }

        if (message.contains("buy") || message.contains("purchase") || message.contains("order")) {
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
