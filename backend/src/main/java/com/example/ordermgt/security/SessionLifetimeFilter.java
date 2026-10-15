package com.example.ordermgt.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 会话的绝对上限（US-6.4）。容器的空闲超时（{@code server.servlet.session.timeout}）管得住"多久没动"， 管不住"连着动了 12
 * 小时"——servlet 会话没有绝对寿命。这一层在登录时记下起点，之后每一次请求都拿它 比一比，超了就先把会话作废。
 *
 * <p>位置很关键：它排在安全链之前（{@code HIGHEST_PRECEDENCE}），于是作废发生在鉴权之前——后续的安全 链看到的是"没有会话"，请求以 401
 * 收场（SecurityConfig 的 entry point），而不是带着一个已经过期的身份 继续走。过期是"请重新登录"，不是"你没有权限"。
 *
 * <p>没有 {@link #STARTED_AT} 的会话（例如测试里直接塞进 SecurityContext 的请求）不判过期：记不下起点就不该 替它假设一个。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionLifetimeFilter extends OncePerRequestFilter {

    /** 登录时写进会话的起点，也是这里唯一的判据。两边共用这一个键，名字只写一处。 */
    public static final String STARTED_AT = "SESSION_STARTED_AT";

    private final Duration absoluteLifetime;

    public SessionLifetimeFilter(
            @Value("${security.session.absolute-lifetime:12h}") Duration absoluteLifetime) {
        this.absoluteLifetime = absoluteLifetime;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && expired(session)) {
            session.invalidate();
        }
        filterChain.doFilter(request, response);
    }

    private boolean expired(HttpSession session) {
        Object startedAt = session.getAttribute(STARTED_AT);
        return startedAt instanceof Instant at
                && Duration.between(at, Instant.now()).compareTo(absoluteLifetime) >= 0;
    }
}
