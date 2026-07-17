package com.ecommerce.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

/** eventId (Phase 8): used as the dedupe key into processed_events — see ARCHITECTURE.md. */
public record InventoryReservedEvent(UUID eventId, Long orderId, Long userId, BigDecimal totalAmount) {
}
