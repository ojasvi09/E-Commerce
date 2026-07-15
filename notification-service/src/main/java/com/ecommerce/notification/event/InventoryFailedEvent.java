package com.ecommerce.notification.event;

public record InventoryFailedEvent(Long orderId, Long userId, String reason) {
}
