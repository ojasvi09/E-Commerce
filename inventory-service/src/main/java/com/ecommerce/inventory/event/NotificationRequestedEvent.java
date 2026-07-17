package com.ecommerce.inventory.event;

import java.util.UUID;

/** eventId (Phase 8): see InventoryReservedEvent's javadoc. */
public record NotificationRequestedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        String message
) {
}
