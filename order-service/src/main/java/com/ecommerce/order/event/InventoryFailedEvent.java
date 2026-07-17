package com.ecommerce.order.event;

import java.util.UUID;

/** eventId (Phase 8): used as the dedupe key into processed_events — see ARCHITECTURE.md. */
public record InventoryFailedEvent(UUID eventId, Long orderId, Long userId, String reason) {
}
