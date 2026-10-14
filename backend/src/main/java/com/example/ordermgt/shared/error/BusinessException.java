package com.example.ordermgt.shared.error;

import com.example.ordermgt.shared.api.ErrorCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Seconds the caller is advised to wait, carried onto the {@code Retry-After} header. Only
     * meaningful on 429s; {@code null} means the header is not sent.
     */
    private final Integer retryAfterSeconds;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, (Integer) null);
    }

    public BusinessException(ErrorCode errorCode, String message, Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryAfterSeconds = null;
    }
}
