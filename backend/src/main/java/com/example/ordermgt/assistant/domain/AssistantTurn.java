package com.example.ordermgt.assistant.domain;

import java.util.List;

public record AssistantTurn(String text, List<ProposedToolCall> toolCalls, AiUsage usage) {}
