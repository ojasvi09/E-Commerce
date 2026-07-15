package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.List;

public record OrderCancelledEvent(
        Long orderId,
        Long userId,
        String reason,
        List<OrderCreatedEvent.Item> items
) {
}
