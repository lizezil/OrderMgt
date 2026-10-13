package com.example.ordermgt.assistant.api;

import com.example.ordermgt.assistant.application.AssistantResult;
import com.example.ordermgt.assistant.application.ExecutedTool;
import com.example.ordermgt.assistant.application.MessageView;
import com.example.ordermgt.assistant.domain.AiInvocation;
import com.example.ordermgt.assistant.domain.Conversation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Domain and application shapes to the wire. A {@code Role} travels as its name; usage counters are
 * flattened out of {@link com.example.ordermgt.assistant.domain.AiUsage}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AssistantApiMapper {

    ConversationResponse toResponse(Conversation conversation);

    @Mapping(target = "id", source = "message.id")
    @Mapping(target = "role", source = "message.role")
    @Mapping(target = "content", source = "message.content")
    @Mapping(target = "createdAt", source = "message.createdAt")
    MessageResponse toResponse(MessageView view);

    @Mapping(target = "promptTokens", source = "usage.promptTokens")
    @Mapping(target = "completionTokens", source = "usage.completionTokens")
    ChatResponse toResponse(AssistantResult result);

    ChatResponse.ToolCallView toResponse(ExecutedTool executed);

    InvocationResponse toResponse(AiInvocation invocation);
}
