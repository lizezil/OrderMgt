package com.example.ordermgt.assistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(@NotBlank @Size(max = MESSAGE_MAX) String message) {

    /**
     * The customer message cap, on the way in rather than at the storage layer: a multi-megabyte
     * paste is refused with a 400 the sender can act on, instead of being accepted, hashed,
     * regex-processed and then silently cut to the column width.
     */
    public static final int MESSAGE_MAX = 4000;
}
