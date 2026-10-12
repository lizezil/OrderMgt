package com.example.ordermgt.assistant.domain;

public record AiUsage(int promptTokens, int completionTokens) {

    public static final AiUsage EMPTY = new AiUsage(0, 0);
}
