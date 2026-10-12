package com.example.ordermgt.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Business error codes. HTTP status is carried here so the exception handler
 * never has to guess.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该资源"),
    CONFLICT(HttpStatus.CONFLICT, "状态冲突"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "幂等键冲突"),
    ACTION_EXPIRED(HttpStatus.GONE, "该待确认操作已过期"),
    UPSTREAM_AI_ERROR(HttpStatus.BAD_GATEWAY, "AI 服务调用失败"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
