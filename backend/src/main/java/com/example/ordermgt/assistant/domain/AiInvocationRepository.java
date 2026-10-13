package com.example.ordermgt.assistant.domain;

import java.util.List;

/** Port for model-call audit rows: writing every call, reading one conversation's own. */
public interface AiInvocationRepository {

    AiInvocation save(AiInvocation invocation);

    /** One conversation's model calls, newest first. */
    List<AiInvocation> findByConversationId(Long conversationId);
}
