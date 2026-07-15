package com.ecommerce.notification.event;

public record NotificationRequestedEvent(
        Long orderId,
        Long userId,
        String message
) {
}
