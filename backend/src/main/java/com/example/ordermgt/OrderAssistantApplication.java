package com.example.ordermgt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is on for the pending-action expiry sweep. */
@SpringBootApplication
@EnableScheduling
public class OrderAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderAssistantApplication.class, args);
    }
}
