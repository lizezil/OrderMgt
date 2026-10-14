package com.example.ordermgt.order.api;

import com.example.ordermgt.idempotency.application.IdempotencyGuard;
import com.example.ordermgt.order.application.OrderService;
import com.example.ordermgt.order.domain.Order;
import com.example.ordermgt.security.CurrentCustomer;
import com.example.ordermgt.shared.api.ApiResponse;
import com.example.ordermgt.shared.api.PageResult;
import com.example.ordermgt.shared.api.Paging;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String CREATE_ORDER = "CREATE_ORDER";

    private final OrderService orderService;
    private final OrderApiMapper mapper;
    private final IdempotencyGuard idempotencyGuard;

    /**
     * The idempotency boundary is the HTTP request, which is exactly where a client retry lands. An
     * omitted {@code Idempotency-Key} means "no protection, run every time".
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> create(
            @CurrentCustomer Long customerId,
            @RequestHeader(value = IdempotencyGuard.KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        OrderResponse response =
                idempotencyGuard.execute(
                        customerId,
                        CREATE_ORDER,
                        idempotencyKey,
                        idempotencyGuard.hashOfJson(request),
                        () ->
                                mapper.toResponse(
                                        orderService.createOrder(customerId, request.toCommands())),
                        OrderResponse.class);
        return ApiResponse.ok(response);
    }

    @GetMapping
    public ApiResponse<PageResult<OrderResponse>> list(
            @CurrentCustomer Long customerId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        PageResult<Order> result =
                orderService.listByCustomer(
                        customerId,
                        page == null ? Paging.FIRST_PAGE : page,
                        size == null ? Paging.DEFAULT_SIZE : size);
        return ApiResponse.ok(
                PageResult.of(
                        result.items().stream().map(mapper::toResponse).toList(),
                        result.total(),
                        result.page(),
                        result.size()));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderResponse> get(
            @CurrentCustomer Long customerId, @PathVariable String orderNo) {
        return ApiResponse.ok(mapper.toResponse(orderService.getByNumber(customerId, orderNo)));
    }
}
