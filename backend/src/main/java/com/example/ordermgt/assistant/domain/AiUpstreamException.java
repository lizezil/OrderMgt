package com.example.ordermgt.assistant.domain;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;

/**
 * The model call itself failed. Carries the audit facts the caller would otherwise lose when the
 * exception unwinds: which model, how long it took, and how it ended -- so the failure can still be
 * written to {@code ai_invocations} by whoever owns the conversation id.
 */
public class AiUpstreamException extends BusinessException {

    private final String model;
    private final int latencyMs;
    private final AiInvocationOutcome outcome;

    public AiUpstreamException(
            ErrorCode errorCode,
            String message,
            Throwable cause,
            String model,
            int latencyMs,
            AiInvocationOutcome outcome) {
        super(errorCode, message, cause);
        this.model = model;
        this.latencyMs = latencyMs;
        this.outcome = outcome;
    }

    public String model() {
        return model;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public AiInvocationOutcome outcome() {
        return outcome;
    }
}
