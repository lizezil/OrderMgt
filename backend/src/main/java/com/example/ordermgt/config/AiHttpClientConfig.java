package com.example.ordermgt.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Timeouts for the blocking model call.
 *
 * <p>Nothing sets one by default: Spring AI 1.1.8 has no {@code spring.ai.*.timeout} property, and
 * Boot's HTTP clients ship without a read timeout, so a stalled upstream would hold the request
 * until the socket dies on its own. The only hook Spring AI offers is the {@code
 * RestClient.Builder} it looks up when it builds the OpenAI client -- Boot auto-configures that
 * builder and applies every {@link RestClientCustomizer} to it, so a customizer is enough to reach
 * it.
 *
 * <p>Streaming is bounded separately, in {@code SpringAiAdapter}: its transport is a WebClient, and
 * no reactive connector (reactor-netty) is on the classpath here, so it resolves to the JDK
 * HttpClient connector, which has no global response timeout to set.
 *
 * <p>Values assume a chat completion: 5s to connect, 60s to read. Combined with the narrowed retry
 * policy (3 attempts, 1s/2s backoff) the worst case is a little over three minutes, not the ~19
 * minutes the framework defaults allowed.
 */
@Configuration
public class AiHttpClientConfig {

    @Bean
    RestClientCustomizer aiRestClientTimeoutCustomizer() {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return builder -> builder.requestFactory(requestFactory);
    }
}
