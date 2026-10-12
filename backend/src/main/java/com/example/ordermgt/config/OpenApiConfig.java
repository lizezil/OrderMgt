package com.example.ordermgt.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderMgtOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("OrderMgt API")
                                .version("1.0.0")
                                .description(
                                        "Order management where the AI assistant proposes and a customer confirms."));
    }
}
