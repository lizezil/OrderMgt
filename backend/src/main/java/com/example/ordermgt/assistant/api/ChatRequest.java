package com.example.ordermgt.assistant.api;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {}
