package com.ecommerce.order.event;

import com.ecommerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens for the downstream outcomes of an order (inventory failing to reserve stock,
 * or payment succeeding/failing) and updates the order's status accordingly. This is
 * the async replacement for Phase 2's synchronous try/catch in OrderService.create().
 */
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderService orderService;

    // Each listener overrides spring.json.value.default.type for its own topic so the
    // shared consumer factory (configured with use.type.headers=false in application.yml)
    // knows which local event class to deserialize that topic's JSON into.

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_FAILED,
            groupId = "order-service",
            properties = "spring.json.value.default.type=com.ecommerce.order.event.InventoryFailedEvent")
    public void onInventoryFailed(InventoryFailedEvent event) {
        log.info("Received InventoryFailedEvent for orderId {}: {}", event.orderId(), event.reason());
        orderService.markCancelled(event.orderId(), "Inventory reservation failed: " + event.reason());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_SUCCESSFUL,
            groupId = "order-service",
            properties = "spring.json.value.default.type=com.ecommerce.order.event.PaymentSuccessfulEvent")
    public void onPaymentSuccessful(PaymentSuccessfulEvent event) {
        log.info("Received PaymentSuccessfulEvent for orderId {}", event.orderId());
        orderService.markConfirmed(event.orderId());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "order-service",
            properties = "spring.json.value.default.type=com.ecommerce.order.event.PaymentFailedEvent")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent for orderId {}: {}", event.orderId(), event.reason());
        orderService.markCancelled(event.orderId(), "Payment failed: " + event.reason());
    }
}
