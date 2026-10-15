package com.example.ordermgt.config;

import com.example.ordermgt.shared.logging.MdcPropagatingExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    /**
     * A small dedicated, bounded pool for streaming turns.
     *
     * <p>Streaming blocks a thread for as long as the model takes (up to the stream timeout), so it
     * gets its own pool rather than borrowing Tomcat's request threads or the shared application
     * executor -- a handful of slow model calls must not be able to stall the rest of the app.
     *
     * <p>Deliberately NOT {@link Executors#newFixedThreadPool}: that pairs a fixed thread count
     * with an <b>unbounded</b> {@link java.util.concurrent.LinkedBlockingQueue}, so under load it
     * accepts every request and lets them queue without limit instead of shedding. A bounded queue
     * plus {@link ThreadPoolExecutor.AbortPolicy} makes saturation explicit: past {@code
     * max-concurrent} running + {@code queue-capacity} waiting, a {@link
     * java.util.concurrent.RejectedExecutionException} surfaces to the caller, which answers 503
     * rather than opening a stream that would only time out waiting for a thread.
     *
     * <p>A stream turn writes its logs on a worker thread, so the pool is {@link
     * MdcPropagatingExecutor}: every task carries the submitting thread's correlation ids and
     * restores the worker's state afterwards.
     */
    @Bean(name = "assistantStreamExecutor", destroyMethod = "shutdown")
    public Executor assistantStreamExecutor(
            @Value("${assistant.stream.max-concurrent:20}") int maxConcurrent,
            @Value("${assistant.stream.queue-capacity:100}") int queueCapacity) {
        return new MdcPropagatingExecutor(maxConcurrent, queueCapacity, "assistant-stream");
    }
}
