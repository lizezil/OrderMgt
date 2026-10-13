package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.AiUsage;
import java.util.List;

public record AssistantResult(
        Long conversationId,
        String reply,
        List<ExecutedTool> executed,
        List<ActionCard> actions,
        AiUsage usage,
        String model,
        int latencyMs) {}
