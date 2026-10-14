package com.example.ordermgt.assistant.infrastructure.ai.prompt;

/**
 * The canned replies of the offline stand-in ({@code MockAiAdapter}).
 *
 * <p>They live beside {@link AiPrompts} rather than inside the adapter so that all assistant prose
 * -- real and mock -- is found in one place; the two differ only in who writes it.
 *
 * <p>Chinese, like the demo prompts this stub is driven with: the standing prompt now asks the
 * model to answer in the customer's language, and a stub that can only answer in one language
 * should answer in the one its own triggers are written in.
 */
public final class OfflinePrompts {

    /**
     * Names the fake turn in the audit table and the UI, so a mock is never mistaken for a model.
     */
    public static final String MODEL = "offline-mock";

    /** Keyword: "cancel". */
    public static String cancelProposal(String orderNumber) {
        return "好的，我准备把订单 " + orderNumber + " 取消，请确认。";
    }

    /** Keyword: list / what / how many / check / query / show. */
    public static final String LOOKING_UP_ORDERS = "我查一下你自己的订单。";

    /** Keyword: buy / purchase / order, with a product in the message. */
    public static final String ORDER_PREPARED = "我已经为你准备好订单，请确认。";

    /** Keyword: buy / purchase / order, without a product to place. */
    public static final String ASK_FOR_SKU = "（离线模式）请告诉我 sku 和数量，例如「帮我下单 2 个 DOODAD-04」。";

    /** Nothing recognised. */
    public static final String UNKNOWN_REQUEST =
            "（离线模式）我是一个没有接真实模型的桩助手。可以试试「我有哪些订单」或「帮我下单 2 个 DOODAD-04」。";

    private OfflinePrompts() {}
}
