package com.example.ordermgt.assistant.domain;

/**
 * 模型交给一个只读工具的参数，服务端执行不了（ADR-0016：例如 {@code queryMyOrders} 的 {@code limit} 越界）。
 *
 * <p>它带着出错的工具名和那串参数，因为回喂的那一句必须自己说清是谁、拿什么被拒的：模型的提议从不落库， 到重问时它已经看不见自己刚才提过什么。
 *
 * <p>变更工具的非法参数不归这里管（ADR-0001）：那一条路要等顾客确认，服务端校验发生在确认时，届时报 400。
 */
public class ToolArgumentsRejectedException extends RuntimeException {

    private final String toolName;
    private final String argumentsJson;

    public ToolArgumentsRejectedException(
            String toolName, String argumentsJson, String reason, Throwable cause) {
        super("tool arguments refused: " + toolName + " " + argumentsJson + " -- " + reason, cause);
        this.toolName = toolName;
        this.argumentsJson = argumentsJson;
    }

    /** Which call to name in the observation fed back to the model. */
    public String toolName() {
        return toolName;
    }

    public String argumentsJson() {
        return argumentsJson;
    }

    /** The server's own words about why this argument set cannot run. */
    public String reason() {
        return getCause() == null ? getMessage() : getCause().getMessage();
    }
}
