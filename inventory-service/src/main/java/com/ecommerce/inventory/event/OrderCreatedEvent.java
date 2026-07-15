package com.ecommerce.inventory.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * Local copy of order-service's event (loose coupling — see project cross-cutting
 * rule: duplicate a small DTO rather than share a domain model across services).
 * Only fields this service actually needs are kept; totalAmount/price are unused here
 * but included so the JSON shape matches the producer's record exactly.
 */
public record OrderCreatedEvent(
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        List<Item> items
) {
    public record Item(Long productId, Integer quantity, BigDecimal price) {
    }
}
