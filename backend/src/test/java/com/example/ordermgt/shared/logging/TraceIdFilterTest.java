package com.example.ordermgt.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Business rule: 每条请求一个 traceId——外部带进来且干净的照用，其余的自己生成；响应回显同一个 id，请求结束后 请求线程的 MDC
 * 清空。日志行是可以被伪造的输入直接写进去的，所以边界（长度、字符、谁算登录态）都必须钉住。
 */
class TraceIdFilterTest {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void cleanUp() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void anInboundTraceIdIsHonoredPutInMdcAndEchoedBack() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "client-trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] insideChain = new String[1];

        filter.doFilter(
                request, response, (req, res) -> insideChain[0] = MDC.get(MdcContext.TRACE_ID));

        assertThat(insideChain[0]).isEqualTo("client-trace-1");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("client-trace-1");
        // 请求结束，请求线程的 MDC 清干净：容器线程下一个请求还要用。
        assertThat(MDC.get(MdcContext.TRACE_ID)).isNull();
    }

    @Test
    void aMissingTraceIdIsGeneratedFreshPerRequestAndEchoed() throws Exception {
        String first = traceIdOf(new MockHttpServletRequest());
        String second = traceIdOf(new MockHttpServletRequest());

        assertThat(first).matches(UUID_PATTERN);
        assertThat(second).matches(UUID_PATTERN).isNotEqualTo(first);
    }

    @Test
    void aHeaderThatCouldForgeALogLineIsReplacedByAGeneratedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "bad trace id with spaces");

        String used = traceIdOf(request);

        assertThat(used).matches(UUID_PATTERN);
    }

    @Test
    void anOverlongHeaderIsNotTrustedEither() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "x".repeat(65));

        assertThat(traceIdOf(request)).matches(UUID_PATTERN);
    }

    @Test
    void theAuthenticatedCustomerIdTravelsWithTheTrace() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("1001", null, List.of()));
        String[] seen = new String[1];

        filter.doFilter(
                newReq(),
                new MockHttpServletResponse(),
                (req, res) -> seen[0] = MDC.get(MdcContext.CUSTOMER_ID));

        assertThat(seen[0]).isEqualTo("1001");
    }

    @Test
    void anAnonymousSessionCarriesNoCustomerId() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "key",
                                "anonymousUser",
                                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        String[] seen = new String[1];

        filter.doFilter(
                newReq(),
                new MockHttpServletResponse(),
                (req, res) -> seen[0] = MDC.get(MdcContext.CUSTOMER_ID));

        assertThat(seen[0]).isNull();
    }

    @Test
    void aRequestWithNoAuthenticationCarriesNoCustomerId() throws Exception {
        String[] seen = new String[1];

        filter.doFilter(
                newReq(),
                new MockHttpServletResponse(),
                (req, res) -> seen[0] = MDC.get(MdcContext.CUSTOMER_ID));

        assertThat(seen[0]).isNull();
    }

    /** Runs the filter over a fresh request and reports the trace id it settled on. */
    private String traceIdOf(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return UUID.fromString(response.getHeader(TraceIdFilter.HEADER)).toString();
    }

    private static MockHttpServletRequest newReq() {
        return new MockHttpServletRequest();
    }
}
