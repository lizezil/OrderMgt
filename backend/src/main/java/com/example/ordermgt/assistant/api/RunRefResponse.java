package com.example.ordermgt.assistant.api;

/**
 * The id of the Run a turn just opened, sent to the streaming client the moment it exists. It is
 * what the stop button needs -- the run id is minted inside the turn, after the stream is already
 * open, so it cannot ride on an earlier frame.
 */
public record RunRefResponse(Long runId) {}
