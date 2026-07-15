package com.ecommerce.payment.event;

import com.ecommerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to inventory having been successfully reserved for an order by charging
 * payment for the order total. Publishes PaymentSuccessfulEvent or PaymentFailedEvent
 * — Order Service listens for both to update the order's status. This replaces the
 * direct POST /api/payments call order-service made in Phase 2.
 *
 * Payment "failure" here is simulated (no real payment gateway integration exists yet,
 * same as Phase 1/2) — this service always succeeds unless persistence itself throws.
 *
 * <p>The listener method itself is intentionally NOT @Transactional — see
 * PaymentService.chargeAndPublish's javadoc and inventory-service's
 * OrderCreatedEventListener for the full rationale: a shared transaction across both the
 * charge-and-publish-success path and the catch-and-publish-failure path lets a failure
 * in the first poison the outbox write in the second, since Spring marks the whole
 * transaction rollback-only the moment any exception propagates through it, even after
 * being caught. chargeAndPublish is its own complete transaction; the catch block below
 * runs with no surrounding transaction, so each publish* call there commits independently.
 */
@Component
@RequiredArgsConstructor
public class InventoryReservedEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservedEventListener.class);

    private final PaymentService paymentService;
    private final PaymentEventProducer eventProducer;

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_RESERVED,
            groupId = "payment-service",
            properties = "spring.json.value.default.type=com.ecommerce.payment.event.InventoryReservedEvent")
    public void onInventoryReserved(InventoryReservedEvent event) {
        log.info("Received InventoryReservedEvent for orderId {}, charging {}",
                event.orderId(), event.totalAmount());
        try {
            paymentService.chargeAndPublish(event.orderId(), event.userId(), event.totalAmount());
            eventProducer.publishNotificationRequested(new NotificationRequestedEvent(
                    event.orderId(), event.userId(),
                    "Your order #" + event.orderId() + " has been confirmed. Amount charged: " + event.totalAmount()));
        } catch (RuntimeException ex) {
            log.error("Payment failed for orderId {}", event.orderId(), ex);
            eventProducer.publishFailed(new PaymentFailedEvent(event.orderId(), event.userId(), ex.getMessage()));
            eventProducer.publishNotificationRequested(new NotificationRequestedEvent(
                    event.orderId(), event.userId(),
                    "Your order #" + event.orderId() + " was cancelled: payment failed (" + ex.getMessage() + ")"));
        }
    }
}
