package com.ecommerce.payment.event;

import com.ecommerce.payment.dto.PaymentRequest;
import com.ecommerce.payment.entity.PaymentStatus;
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
 */
@Component
@RequiredArgsConstructor
public class InventoryReservedEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservedEventListener.class);

    private final PaymentService paymentService;
    private final PaymentEventProducer eventProducer;

    @KafkaListener(topics = KafkaTopics.INVENTORY_RESERVED, groupId = "payment-service")
    public void onInventoryReserved(InventoryReservedEvent event) {
        log.info("Received InventoryReservedEvent for orderId {}, charging {}",
                event.orderId(), event.totalAmount());
        try {
            paymentService.create(new PaymentRequest(event.orderId(), event.totalAmount(), PaymentStatus.SUCCESSFUL));
            eventProducer.publishSuccessful(
                    new PaymentSuccessfulEvent(event.orderId(), event.userId(), event.totalAmount()));
        } catch (RuntimeException ex) {
            log.error("Payment failed for orderId {}", event.orderId(), ex);
            eventProducer.publishFailed(new PaymentFailedEvent(event.orderId(), event.userId(), ex.getMessage()));
        }
    }
}
