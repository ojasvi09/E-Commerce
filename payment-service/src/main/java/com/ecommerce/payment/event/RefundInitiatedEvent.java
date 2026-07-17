package com.ecommerce.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

/** eventId (Phase 8): see PaymentSuccessfulEvent's javadoc. Nothing consumes this topic yet. */
public record RefundInitiatedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        BigDecimal amount,
        String reason
) {
}
