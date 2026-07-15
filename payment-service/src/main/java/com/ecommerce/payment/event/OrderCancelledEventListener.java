package com.ecommerce.payment.event;

import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.entity.PaymentStatus;
import com.ecommerce.payment.service.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order Service only publishes OrderCancelledEvent when inventory HAD already been
 * reserved for the order (i.e. cancellation happened after this service already tried
 * to charge it) — see order-service's OrderService.markCancelled. If this service has a
 * SUCCESSFUL payment on file for that order, the charge needs reversing: publish
 * RefundInitiatedEvent (simulated — no real payment gateway, same as charging itself).
 *
 * <p>@Transactional (Phase 6) so the outbox row written by eventProducer.publishRefundInitiated
 * commits atomically — no domain entity is mutated on this path (refund is simulated,
 * no Payment status change), but the outbox write itself still needs a transaction.
 */
@Component
@RequiredArgsConstructor
public class OrderCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledEventListener.class);

    private final PaymentService paymentService;
    private final PaymentEventProducer eventProducer;

    @Transactional
    @KafkaListener(
            topics = KafkaTopics.ORDER_CANCELLED,
            groupId = "payment-service",
            properties = "spring.json.value.default.type=com.ecommerce.payment.event.OrderCancelledEvent")
    public void onOrderCancelled(OrderCancelledEvent event) {
        Optional<PaymentResponse> payment = paymentService.findByOrderId(event.orderId());
        if (payment.isEmpty() || payment.get().status() != PaymentStatus.SUCCESSFUL) {
            log.info("OrderCancelledEvent for orderId {}: no successful payment on file, nothing to refund",
                    event.orderId());
            return;
        }
        log.info("Refunding orderId {}: {}", event.orderId(), payment.get().amount());
        eventProducer.publishRefundInitiated(
                new RefundInitiatedEvent(event.orderId(), event.userId(), payment.get().amount(), event.reason()));
    }
}
