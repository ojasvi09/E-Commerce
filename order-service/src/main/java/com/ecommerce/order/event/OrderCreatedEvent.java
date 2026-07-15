package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.List;

public record OrderCreatedEvent(
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        List<Item> items
) {
    public record Item(Long productId, Integer quantity, BigDecimal price) {
    }
}
