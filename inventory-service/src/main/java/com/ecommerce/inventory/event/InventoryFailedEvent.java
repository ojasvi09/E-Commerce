package com.ecommerce.inventory.event;

public record InventoryFailedEvent(Long orderId, Long userId, String reason) {
}
