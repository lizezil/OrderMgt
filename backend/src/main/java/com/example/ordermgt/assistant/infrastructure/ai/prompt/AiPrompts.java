package com.example.ordermgt.assistant.infrastructure.ai.prompt;

/**
 * Every piece of assistant prose that must be found and changed in one place lives here: what the
 * service sends to the model, and the fixed sentences it says on the model's own behalf when the
 * turn goes off script (US-1.11).
 *
 * <p>Only the text lives here: the class that owns the data still assembles it into these templates
 * (the live catalog rows, the last confirmed order and the awaiting proposals by {@code
 * AssistantGroundingProvider}), so a prompt stays a single edit without dragging the data it is
 * filled with along with it.
 */
public final class AiPrompts {

    /**
     * The standing system prompt, prepended to every model call.
     *
     * <p>The capabilities paragraph is the prose copy of the tool whitelist (附录 B of docs/用户故事.md):
     * the model may claim nothing beyond it, and an honest "here no" beats an invented process.
     * Adding a tool means extending that sentence too (US-1.10).
     */
    public static final String SYSTEM =
            """
            You are an order management assistant. You may call tools to query or change
            a customer's orders.

            Your capabilities, in full: list the customer's orders, look one order up by
            its number, propose to place an order, propose to cancel or change an order's
            status, and answer questions about the items and their unit prices. Anything
            else -- refunds, address changes, invoices and the like -- this assistant
            cannot do: say plainly that you cannot do it here. Never invent a process and
            never pretend something was done; when the request is unclear, ask instead of
            guessing.
            Rules:
            - Use only the provided tools; do not invent tools or arguments.
            - Mutating tools (create / cancel / change status) are only "proposals"; the
              real execution happens after the customer confirms, so when calling them say
              in the text what you intend to do.
            - Never guess or fabricate order numbers, skus or amounts; placing an order
              fills in only sku and quantity.
            - Answer in the language the customer wrote in.
            - Do not restate what a tool returned: the service renders a tool's result
              for the customer itself, and repeating it shows the same list twice.
            - A message framed "[Tool result]" may also say a call was refused: those
              arguments were wrong and nothing ran. Either call the tool again with
              corrected arguments, or ask the customer what they meant -- never repeat
              the arguments that were refused.
            """;

    /**
     * Header before the live catalog. Injected so the model copies a real sku instead of inventing
     * one (ADR-0006).
     */
    public static final String CATALOG_HEADER = "Available items (take skus exactly from here):";

    /** One catalog row: sku, name, unit price, then a newline. */
    public static final String CATALOG_ROW = "- %s %s unit price %s\n";

    /**
     * Appended when the catalog had to be cut to fit the prompt budget: without it the model would
     * take the truncated list for the whole catalog and refuse items it cannot see.
     */
    public static final String CATALOG_TRUNCATED =
            "(The catalog is too large to list in full; the items above are a partial view.)\n";

    /**
     * Header before the order this conversation most recently placed. Injected so "查看刚刚这个订单" has an
     * answer: the number is on the confirmed action, never in the transcript, so a model without
     * this line can only list every order and hope the newest one is the one meant.
     */
    public static final String LAST_CONFIRMED_ORDER_HEADER =
            "The order this conversation placed most recently:";

    /** That order's number, then what was confirmed. */
    public static final String LAST_CONFIRMED_ORDER_ROW = "- %s (%s)\n";

    /**
     * Header before the proposals of this conversation still awaiting the customer's confirmation
     * (US-1.13). Injected so "that one I just proposed" and "the second one" have determinate
     * referents while a card is still pending -- the same job {@link #LAST_CONFIRMED_ORDER_HEADER}
     * does after one is confirmed. Rows keep the order they were made in, the way the thread reads;
     * the section is absent, never empty-headed, when nothing is waiting.
     */
    public static final String AWAITING_CONFIRMATION_HEADER =
            "Proposals of this conversation still awaiting the customer's confirmation, in the order they were made:";

    /**
     * One waiting proposal: the tool it would run, then the card's own summary. Never its token.
     */
    public static final String AWAITING_CONFIRMATION_ROW = "- %s: %s\n";

    /**
     * 模型走样时服务端替它说的那句（US-1.11）：承认这次帮不上，并指出它真正能做的事。与只读结果的 渲染、{@code
     * AssistantService.SAFE_DEFAULT_NOTE} 同一条路子——保证由确定性代码给，不靠模型自觉。
     */
    public static final String OFF_SCRIPT_REPLY =
            "这次我没能帮上忙，请再试一次。我能做的是：查订单、查单张订单、提议下单、提议取消或修改订单状态，以及回答商品和单价。";

    private AiPrompts() {}
}
