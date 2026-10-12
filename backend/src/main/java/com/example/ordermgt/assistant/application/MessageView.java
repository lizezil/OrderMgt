package com.example.ordermgt.assistant.application;

import com.example.ordermgt.assistant.domain.Message;

/**
 * A message plus the card it presented, if any. Letting the frontend rebuild a thread from history
 * alone means a confirmed card is still shown as a card -- with its outcome.
 */
public record MessageView(Message message, ActionCard action) {}
