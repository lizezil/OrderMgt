package com.example.ordermgt.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    /**
     * A small dedicated pool for streaming turns.
     *
     * <p>Streaming blocks a thread for as long as the model takes, so it gets its own pool rather
     * than borrowing Tomcat's request threads or the shared application executor -- a handful of
     * slow model calls must not be able to stall the rest of the app.
     */
    @Bean(name = "assistantStreamExecutor", destroyMethod = "shutdown")
    public Executor assistantStreamExecutor() {
        return Executors.newFixedThreadPool(
                4,
                runnable -> {
                    Thread thread = new Thread(runnable, "assistant-stream");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
