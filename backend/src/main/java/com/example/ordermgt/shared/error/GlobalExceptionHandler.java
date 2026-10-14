package com.example.ordermgt.shared.error;

import com.example.ordermgt.shared.api.ApiResponse;
import com.example.ordermgt.shared.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("business error: code={} message={}", ex.errorCode(), ex.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.errorCode().status());
        // A 429 without a wait hint leaves the client guessing; carry the advised seconds onto the
        // standard header so any HTTP client can back off without parsing our envelope.
        if (ex.retryAfterSeconds() != null) {
            builder =
                    builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(ApiResponse.error(ex.errorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(
            MethodArgumentNotValidException ex) {
        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(e -> e.getField() + ": " + e.getDefaultMessage())
                        .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParamValidation(
            ConstraintViolationException ex) {
        return badRequest(
                ex.getConstraintViolations().stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; ")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
        return badRequest("missing request header: " + ex.getHeaderName());
    }

    /** A body the JSON parser rejected is the client's mistake, not a server fault. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException ex) {
        return badRequest("request body is not valid JSON");
    }

    /** Covers the identity header ("X-Customer-Id: abc") and typed query params ("?page=abc"). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return badRequest(
                "invalid value for "
                        + ex.getName()
                        + ": expected "
                        + (ex.getRequiredType() == null
                                ? "a valid value"
                                : ex.getRequiredType().getSimpleName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return badRequest("missing request parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(
                        ApiResponse.error(
                                "UNSUPPORTED_MEDIA_TYPE", "content type must be application/json"));
    }

    // Let Spring's own web exceptions keep their status instead of becoming a 500.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoResourceFoundException ex) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(
                        ApiResponse.error(
                                ErrorCode.NOT_FOUND.name(),
                                "no such endpoint: " + ex.getResourcePath()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(
                        ApiResponse.error(
                                "METHOD_NOT_ALLOWED",
                                "unsupported request method: " + ex.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(
                        ApiResponse.error(
                                ErrorCode.INTERNAL_ERROR.name(),
                                ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String detail) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.name(), detail));
    }
}
