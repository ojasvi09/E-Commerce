package com.ecommerce.inventory.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enqueues events onto the transactional outbox (Phase 6) instead of sending to Kafka
 * directly — see OutboxEventService/OutboxPoller for why. Callers must invoke these
 * methods from within the same @Transactional method/call chain that made the stock
 * mutation the event describes, so the outbox row commits atomically with it.
 */
@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private final OutboxEventService outboxEventService;

    public void publishReserved(InventoryReservedEvent event) {
        outboxEventService.enqueue(KafkaTopics.INVENTORY_RESERVED, event.orderId().toString(), event);
    }

    public void publishFailed(InventoryFailedEvent event) {
        outboxEventService.enqueue(KafkaTopics.INVENTORY_FAILED, event.orderId().toString(), event);
    }

    public void publishNotificationRequested(NotificationRequestedEvent event) {
        outboxEventService.enqueue(KafkaTopics.NOTIFICATION_REQUESTED, event.orderId().toString(), event);
    }
}
