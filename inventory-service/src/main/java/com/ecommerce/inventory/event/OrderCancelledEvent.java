package com.ecommerce.inventory.event;

import java.util.List;

public record OrderCancelledEvent(
        Long orderId,
        Long userId,
        String reason,
        List<OrderCreatedEvent.Item> items
) {
}
