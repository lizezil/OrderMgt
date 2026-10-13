package com.example.ordermgt.assistant.domain;

/**
 * Whether the customer has stopped the run behind a streaming call (ADR-0012). The model call polls
 * it as chunks arrive and aborts the upstream stream the moment it flips -- a cooperative cancel,
 * because aborting the billable part is the entire point of a stop button: a stop that kept paying
 * for tokens nobody wants would be theatre.
 *
 * <p>One thread flips it (the stop request), the stream thread reads it. Delivery is best-effort
 * (ADR-0009); this signal is about the turn, and it only ever stops one that is still running.
 *
 * <p>The blocking transport has no incremental hook to observe it, so it cannot be stopped from the
 * server while the call is in flight -- see {@link #NEVER} and the known gaps in the README.
 */
@FunctionalInterface
public interface RunCancellation {

    boolean isCancelled();

    /** For a call that nothing can cancel, e.g. the blocking transport. */
    RunCancellation NEVER = () -> false;
}
