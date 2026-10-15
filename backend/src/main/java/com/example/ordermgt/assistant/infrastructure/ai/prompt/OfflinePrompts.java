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

    /**
     * Keyword: 退款 / refund, 发票 / invoice, 地址 / address -- a real order system's asks that this
     * assistant has no capability for. Says so plainly and lists what it can actually do, mirroring
     * what the real model is told to answer (US-1.10). Deliberately has no "（离线模式）" prefix: this is
     * the honest answer an online assistant gives too, unlike {@link #UNKNOWN_REQUEST}.
     */
    public static final String OUT_OF_CAPABILITY =
            "退款、改地址、开发票这些事我做不了，这里没有这些能力。" + "我能帮你查订单、查单张订单、提议下单、提议取消或修改订单状态，以及回答商品和单价。";

    /** Keyword: "cancel". */
    public static String cancelProposal(String orderNumber) {
        return "好的，我准备把订单 " + orderNumber + " 取消，请确认。";
    }

    /** Keyword: list / what / how many / check / query / show. */
    public static final String LOOKING_UP_ORDERS = "我查一下你自己的订单。";

    /** Keyword: 最近 / 最新 / 刚刚 -- one order, not a page of them. */
    public static final String LOOKING_UP_LATEST = "我看看你最近的一单。";

    /** Keyword: buy / purchase / order, with a product in the message. */
    public static final String ORDER_PREPARED = "我已经为你准备好订单，请确认。";

    /** Keyword: buy / purchase / order, without a product to place. */
    public static final String ASK_FOR_SKU = "（离线模式）请告诉我 sku 和数量，例如「帮我下单 2 个 DOODAD-04」。";

    /** Nothing recognised. */
    public static final String UNKNOWN_REQUEST =
            "（离线模式）我是一个没有接真实模型的桩助手。可以试试「我有哪些订单」或「帮我下单 2 个 DOODAD-04」。";

    private OfflinePrompts() {}
}
