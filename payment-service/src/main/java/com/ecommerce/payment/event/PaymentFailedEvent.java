package com.ecommerce.payment.event;

import java.util.UUID;

/** eventId (Phase 8): see PaymentSuccessfulEvent's javadoc. */
public record PaymentFailedEvent(UUID eventId, Long orderId, Long userId, String reason) {
}
