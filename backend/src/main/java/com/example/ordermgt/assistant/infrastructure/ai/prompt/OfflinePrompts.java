package com.example.ordermgt.assistant.infrastructure.ai.prompt;

/**
 * The canned replies of the offline stand-in ({@code MockAiAdapter}).
 *
 * <p>They live beside {@link AiPrompts} rather than inside the adapter so that all assistant prose
 * -- real and mock -- is found in one place; the two differ only in who writes it.
 */
public final class OfflinePrompts {

    /**
     * Names the fake turn in the audit table and the UI, so a mock is never mistaken for a model.
     */
    public static final String MODEL = "offline-mock";

    /** Keyword: "cancel". */
    public static String cancelProposal(String orderNumber) {
        return "Sure, I am ready to cancel order " + orderNumber + ", please confirm.";
    }

    /** Keyword: list / what / how many / check / query / show. */
    public static final String LOOKING_UP_ORDERS = "Let me look up your own orders.";

    /** Keyword: buy / purchase / order, with a product in the message. */
    public static final String ORDER_PREPARED = "I have prepared an order for you, please confirm.";

    /** Keyword: buy / purchase / order, without a product to place. */
    public static final String ASK_FOR_SKU =
            "(offline mode) Please tell me the SKU and quantity, for example "
                    + "\"help me order 2 DOODAD-04\".";

    /** Nothing recognised. */
    public static final String UNKNOWN_REQUEST =
            "(offline mode) I am a stub assistant without a real model. Try "
                    + "\"what orders do I have\" or \"help me order 2 DOODAD-04\".";

    private OfflinePrompts() {}
}
