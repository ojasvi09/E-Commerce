package com.ecommerce.order.event;

public record InventoryFailedEvent(Long orderId, Long userId, String reason) {
}
