package com.ecommerce.order.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enqueues events onto the transactional outbox (Phase 6) instead of sending to Kafka
 * directly — see OutboxEventService/OutboxPoller for why. Callers must invoke these
 * methods from within the same @Transactional method that made the domain change the
 * event describes, so the outbox row commits atomically with it.
 */
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private final OutboxEventService outboxEventService;

    public void publishOrderCreated(OrderCreatedEvent event) {
        outboxEventService.enqueue(KafkaTopics.ORDER_CREATED, event.orderId().toString(), event);
    }

    public void publishOrderCancelled(OrderCancelledEvent event) {
        outboxEventService.enqueue(KafkaTopics.ORDER_CANCELLED, event.orderId().toString(), event);
    }

    public void publishShipmentCreated(ShipmentCreatedEvent event) {
        outboxEventService.enqueue(KafkaTopics.SHIPMENT_CREATED, event.orderId().toString(), event);
    }
}
