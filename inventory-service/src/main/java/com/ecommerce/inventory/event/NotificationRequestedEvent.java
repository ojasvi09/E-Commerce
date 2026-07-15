package com.ecommerce.inventory.event;

public record NotificationRequestedEvent(
        Long orderId,
        Long userId,
        String message
) {
}
