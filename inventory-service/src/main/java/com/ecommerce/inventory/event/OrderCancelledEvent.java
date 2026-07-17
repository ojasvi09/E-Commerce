package com.ecommerce.inventory.event;

import java.util.List;
import java.util.UUID;

/** eventId (Phase 8): used as the dedupe key into processed_events — see ARCHITECTURE.md. */
public record OrderCancelledEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        String reason,
        List<OrderCreatedEvent.Item> items
) {
}
