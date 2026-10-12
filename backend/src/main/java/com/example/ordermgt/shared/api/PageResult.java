package com.example.ordermgt.shared.api;

import java.util.List;

public record PageResult<T>(List<T> items, long total, long page, long size) {

    public static <T> PageResult<T> of(List<T> items, long total, long page, long size) {
        return new PageResult<>(items, total, page, size);
    }
}
