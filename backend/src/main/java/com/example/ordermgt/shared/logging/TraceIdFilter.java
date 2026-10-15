package com.example.ordermgt.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opens the trace for every request: one {@code traceId} per request, put into the MDC before the
 * handler runs and echoed back on the response, so a report of "this call went wrong" can be
 * matched to every line the call wrote -- through the model call and onto the streaming pool, where
 * the ids travel with the task ({@link MdcPropagatingExecutor}, {@code ConversationRunQueue}).
 *
 * <p>An inbound {@code X-Trace-Id} is honored only when it is a harmless token (letters, digits,
 * dot, dash, underscore, up to 64 chars): it arrives from outside and goes straight into log lines,
 * so anything else -- whitespace, control characters -- could forge entries and is replaced by a
 * generated id.
 *
 * <p>The customer id is read from the authenticated principal with the same test the
 * {@code @CurrentCustomer} resolver applies. This is a plain servlet filter (last in the chain,
 * after Spring Security), so a request security already refused never reaches it: a 401 leaves
 * without a trace id -- and without a customer id, since none was ever established. Everything that
 * gets this far is correlated end to end.
 *
 * <p>The filter owns the request thread's MDC for the request's whole life and clears the whole map
 * in a finally, so a recycled container thread starts the next request clean. Pool threads are the
 * carry wrapper's responsibility, and the async dispatch a finished SSE stream triggers is
 * deliberately unfiltered ({@link OncePerRequestFilter} skips it by default): the turn it belonged
 * to has already ended.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    /** Safe to paste into a log line: no whitespace, no control characters, bounded length. */
    private static final Pattern INBOUND_ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = inboundTraceId(request.getHeader(HEADER));
        MdcContext.put(MdcContext.TRACE_ID, traceId);
        MdcContext.put(MdcContext.CUSTOMER_ID, authenticatedCustomerId());
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String inboundTraceId(String header) {
        if (header != null && INBOUND_ACCEPTABLE.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    /** On this app's sessions the principal name is the customer id; anonymous means nobody yet. */
    private static String authenticatedCustomerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
