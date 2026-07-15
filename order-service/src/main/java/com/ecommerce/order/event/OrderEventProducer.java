package com.ecommerce.order.event;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        // Key by orderId (as String) so all events for the same order land on the same
        // partition and are processed in order by a given consumer instance.
        kafkaTemplate.send(KafkaTopics.ORDER_CREATED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish OrderCreatedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published OrderCreatedEvent for orderId {}", event.orderId());
                    }
                });
    }

    public void publishOrderCancelled(OrderCancelledEvent event) {
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish OrderCancelledEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published OrderCancelledEvent for orderId {}", event.orderId());
                    }
                });
    }

    public void publishShipmentCreated(ShipmentCreatedEvent event) {
        kafkaTemplate.send(KafkaTopics.SHIPMENT_CREATED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ShipmentCreatedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published ShipmentCreatedEvent for orderId {}", event.orderId());
                    }
                });
    }
}
