package com.example.ordermgt.assistant.domain;

/** Port for writing model-call audit rows. */
public interface AiInvocationRepository {

    AiInvocation save(AiInvocation invocation);
}
