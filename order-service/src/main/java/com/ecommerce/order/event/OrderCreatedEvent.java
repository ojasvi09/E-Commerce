package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * eventId (Phase 8): a random id assigned once when the event is built, carried unchanged
 * through the outbox row's JSON payload and the Kafka message. Consumers use it as the key
 * into their own processed_events table to detect redelivery of this exact event — see
 * ARCHITECTURE.md's "Idempotency" section.
 */
public record OrderCreatedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        List<Item> items
) {
    public record Item(Long productId, Integer quantity, BigDecimal price) {
    }
}
