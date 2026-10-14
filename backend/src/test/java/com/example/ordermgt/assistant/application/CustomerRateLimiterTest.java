package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 业务规则：每个客户每分钟最多放行 N 次 AI 入口（chat 与 stream 都算），超出就是 429 RATE_LIMITED 并带上
 * Retry-After——等到窗口里最早那次请求滑出的秒数，客户端照着等就行。窗口是滑动的：不是"下一分钟重来"， 而是"最早那次过了 60 秒就让出一个名额"。
 *
 * <p>时钟注入是为了让"窗口滑动"这件事可以精确断言，不用 sleep。
 */
class CustomerRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(0);

    private CustomerRateLimiter limiter(int limitPerMinute) {
        return new CustomerRateLimiter(limitPerMinute, clock::get);
    }

    @Test
    void requestsWithinTheLimitAreAdmitted() {
        CustomerRateLimiter limiter = limiter(2);

        assertThatCode(
                        () -> {
                            limiter.admit(1001L);
                            limiter.admit(1001L);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    void theRequestPastTheLimitIsRefusedWith429AndARetryAfter() {
        CustomerRateLimiter limiter = limiter(2);
        limiter.admit(1001L);
        limiter.admit(1001L);

        assertThatThrownBy(() -> limiter.admit(1001L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException business = (BusinessException) ex;
                            assertThat(business.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            // 距离最早那次请求滑出窗口：2 次都在 t=0，60 秒整。
                            assertThat(business.retryAfterSeconds()).isEqualTo(60);
                        });
    }

    @Test
    void theRetryAfterShrinksAsTheOldestRequestAgesOutOfTheWindow() {
        CustomerRateLimiter limiter = limiter(2);
        limiter.admit(1001L);
        clock.addAndGet(30_000);
        limiter.admit(1001L);

        assertThatThrownBy(() -> limiter.admit(1001L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                // 最早那次还有 30 秒滑出，Retry-After 跟着走。
                                assertThat(((BusinessException) ex).retryAfterSeconds())
                                        .isEqualTo(30));
    }

    @Test
    void onceTheWindowSlidesAFreshRequestIsAdmittedAgain() {
        CustomerRateLimiter limiter = limiter(2);
        limiter.admit(1001L);
        limiter.admit(1001L);
        clock.addAndGet(60_001);

        assertThatCode(() -> limiter.admit(1001L)).doesNotThrowAnyException();
    }

    @Test
    void customersAreLimitedIndependently() {
        CustomerRateLimiter limiter = limiter(1);
        limiter.admit(1001L);

        // 别人的额度不受影响：限的是这个客户的洪水，不是所有人的路。
        assertThatCode(() -> limiter.admit(2002L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.admit(1001L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void retryAfterIsNeverZeroSoTheClientAlwaysWaitsAtLeastACecond() {
        CustomerRateLimiter limiter = limiter(1);
        limiter.admit(1001L);
        // 差一点点就滑出：仍然要报至少 1 秒，0 会让客户端原地打转。
        clock.addAndGet(59_500);

        assertThatThrownBy(() -> limiter.admit(1001L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).retryAfterSeconds())
                                        .isEqualTo(1));
    }
}
