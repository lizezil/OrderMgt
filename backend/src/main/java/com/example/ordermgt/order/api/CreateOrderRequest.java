package com.example.ordermgt.order.api;

import com.example.ordermgt.order.application.LineCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(@NotEmpty @Size(max = 100) @Valid List<Line> lines) {

    public List<LineCommand> toCommands() {
        return lines.stream().map(l -> new LineCommand(l.sku(), l.quantity())).toList();
    }

    /**
     * The quantity ceiling is DECIMAL(12,2) arithmetic on the way to the database: beyond a million
     * a plausible unit price overflows the column and the insert dies as a 500 instead of the
     * validation error the client could have fixed.
     */
    public record Line(@NotBlank String sku, @Min(1) @Max(1_000_000) int quantity) {}
}
