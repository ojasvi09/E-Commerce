package com.ecommerce.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record OrderRequest(
        @NotNull(message = "userId is required")
        Long userId,

        @NotEmpty(message = "order must contain at least one item")
        @Valid
        List<OrderItemRequest> items
) {
}
