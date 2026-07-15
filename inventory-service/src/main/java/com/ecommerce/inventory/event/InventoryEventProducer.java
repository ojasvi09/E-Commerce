package com.ecommerce.inventory.event;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryEventProducer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishReserved(InventoryReservedEvent event) {
        kafkaTemplate.send(KafkaTopics.INVENTORY_RESERVED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish InventoryReservedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published InventoryReservedEvent for orderId {}", event.orderId());
                    }
                });
    }

    public void publishFailed(InventoryFailedEvent event) {
        kafkaTemplate.send(KafkaTopics.INVENTORY_FAILED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish InventoryFailedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published InventoryFailedEvent for orderId {}", event.orderId());
                    }
                });
    }

    public void publishNotificationRequested(NotificationRequestedEvent event) {
        kafkaTemplate.send(KafkaTopics.NOTIFICATION_REQUESTED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish NotificationRequestedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published NotificationRequestedEvent for orderId {}", event.orderId());
                    }
                });
    }
}
