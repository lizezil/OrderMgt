package com.example.ordermgt.shared.api;

/**
 * Pagination defaults and bounds -- the one place the numbers live, shared by the orders
 * controller, the order service that clamps them, and the assistant's readonly tool that pages on
 * the customer's behalf.
 */
public final class Paging {

    public static final long FIRST_PAGE = 1L;

    public static final long DEFAULT_SIZE = 20L;

    /**
     * A size outside {@code 1..MAX_SIZE} is clamped back to {@link #DEFAULT_SIZE}, never trusted.
     */
    public static final long MAX_SIZE = 100L;

    private Paging() {}
}
