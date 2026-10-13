package com.example.ordermgt.assistant.domain;

/**
 * Thrown by a streaming call the customer stopped (ADR-0012). Not a failure: the customer asked for
 * it. It carries the turn as far as it got, so the half text can be persisted as an ordinary
 * ASSISTANT message -- the Run is CANCELLED, the audit row says CANCELLED, and no error is shown.
 *
 * <p>Deliberately not a {@link com.example.ordermgt.shared.error.BusinessException}: it never
 * reaches the HTTP layer as an error code, because {@code executeTurn} consumes it and the stream
 * closes with a {@code cancelled} frame.
 */
public class RunCancelledException extends RuntimeException {

    /** The partial turn (may be null when nothing had been produced yet). */
    private final transient AssistantTurn partial;

    public RunCancelledException(AssistantTurn partial) {
        super("run cancelled by the customer");
        this.partial = partial;
    }

    public AssistantTurn partial() {
        return partial;
    }
}
