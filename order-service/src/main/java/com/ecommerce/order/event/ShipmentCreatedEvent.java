package com.ecommerce.order.event;

import java.util.UUID;

/** eventId (Phase 8): see OrderCreatedEvent's javadoc. */
public record ShipmentCreatedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        Long shipmentId
) {
}
