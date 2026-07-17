package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** eventId (Phase 8): see OrderCreatedEvent's javadoc. */
public record OrderCancelledEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        String reason,
        List<OrderCreatedEvent.Item> items
) {
}
