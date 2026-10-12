package com.example.ordermgt.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import com.example.ordermgt.order.application.LineCommand;

import java.util.List;

public record CreateOrderRequest(@NotEmpty @Valid List<Line> lines) {

    public List<LineCommand> toCommands() {
        return lines.stream().map(l -> new LineCommand(l.sku(), l.quantity())).toList();
    }

    public record Line(@NotBlank String sku, @Min(1) int quantity) {
    }
}
