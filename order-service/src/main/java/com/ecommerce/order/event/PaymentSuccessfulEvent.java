package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.UUID;

/** eventId (Phase 8): used as the dedupe key into processed_events — see ARCHITECTURE.md. */
public record PaymentSuccessfulEvent(UUID eventId, Long orderId, Long userId, BigDecimal amount) {
}
