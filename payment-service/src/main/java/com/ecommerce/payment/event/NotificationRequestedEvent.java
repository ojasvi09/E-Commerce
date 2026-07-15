package com.ecommerce.payment.event;

public record NotificationRequestedEvent(
        Long orderId,
        Long userId,
        String message
) {
}
