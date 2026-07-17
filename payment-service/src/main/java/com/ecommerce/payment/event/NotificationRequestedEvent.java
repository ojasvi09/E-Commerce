package com.ecommerce.payment.event;

import java.util.UUID;

/** eventId (Phase 8): see PaymentSuccessfulEvent's javadoc. */
public record NotificationRequestedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        String message
) {
}
