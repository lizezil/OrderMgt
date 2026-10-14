package com.example.ordermgt.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermgt.shared.api.ErrorCode;
import com.example.ordermgt.shared.error.BusinessException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 业务规则：全局限 N 个模型调用同时在飞（阻塞与流式都算，流式从首 token 前持有到流结束）。 满了不是排队等，而是立刻 429
 * RATE_LIMITED——排队会让一个客户的洪水占住每个等待者， 拒绝才让客户端自己决定要不要等 Retry-After 再来。
 *
 * <p>释放是 finally 的事：调用失败也必须把名额还回去，否则一次超时就永久吃掉一个并发位。
 */
class AiConcurrencyGuardTest {

    @Test
    void aCallRunsWhenAPermitIsFree() {
        AiConcurrencyGuard guard = new AiConcurrencyGuard(1);

        assertThat(guard.withPermit(() -> "done")).isEqualTo("done");
    }

    @Test
    void aCallWhileAllPermitsAreHeldIsRefusedWith429() throws Exception {
        AiConcurrencyGuard guard = new AiConcurrencyGuard(1);
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();

        Thread busy =
                new Thread(
                        () -> {
                            try {
                                guard.withPermit(
                                        () -> {
                                            inFlight.countDown();
                                            await(release);
                                            return null;
                                        });
                            } catch (Throwable ex) {
                                backgroundFailure.set(ex);
                            }
                        });
        busy.start();
        assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guard.withPermit(() -> "never"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException business = (BusinessException) ex;
                            assertThat(business.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(business.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
                        });

        release.countDown();
        busy.join(5_000);
        assertThat(backgroundFailure.get()).isNull();
        // 名额还回来了：拒绝过一次的客户下一次照常放行。
        assertThat(guard.withPermit(() -> "again")).isEqualTo("again");
    }

    @Test
    void aFailedCallStillReleasesItsPermit() {
        AiConcurrencyGuard guard = new AiConcurrencyGuard(1);

        assertThatThrownBy(
                        () ->
                                guard.withPermit(
                                        () -> {
                                            throw new IllegalStateException("upstream died");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        // 失败没有吃掉名额：否则三次超时之后服务就永久哑了。
        assertThatCode(() -> guard.withPermit(() -> null)).doesNotThrowAnyException();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
