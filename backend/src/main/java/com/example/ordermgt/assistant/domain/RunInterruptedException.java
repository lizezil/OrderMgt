package com.example.ordermgt.assistant.domain;

/**
 * Thrown by a streaming call whose upstream died after it had already said something. The half text
 * is not garbage: the customer watched it stream by, and ADR-0009's spirit ("a question the
 * assistant answered must not vanish") says it belongs in the transcript. It rides this exception
 * to {@code executeTurn}, which persists it as an ordinary ASSISTANT message annotated as
 * interrupted, closes the Run INTERRUPTED, and lets the transport fail the stream honestly.
 *
 * <p>Unlike {@link RunCancelledException} this IS a failure -- the customer did not ask for it --
 * so the transport still closes with an error frame and the frontend offers its retry; what is
 * different from a plain upstream failure is that the words already produced are kept.
 */
public class RunInterruptedException extends RuntimeException {

    /** The partial turn, as far as the stream got before the upstream died. */
    private final transient AssistantTurn partial;

    public RunInterruptedException(AssistantTurn partial, Throwable cause) {
        super("upstream stream failed mid-reply", cause);
        this.partial = partial;
    }

    public AssistantTurn partial() {
        return partial;
    }
}
