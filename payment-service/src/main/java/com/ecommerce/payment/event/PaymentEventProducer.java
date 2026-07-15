package com.ecommerce.payment.event;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enqueues events onto the transactional outbox (Phase 6) instead of sending to Kafka
 * directly — see OutboxEventService/OutboxPoller for why. Callers must invoke these
 * methods from within the same @Transactional method/call chain that made the Payment
 * write the event describes, so the outbox row commits atomically with it.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final OutboxEventService outboxEventService;

    public void publishSuccessful(PaymentSuccessfulEvent event) {
        outboxEventService.enqueue(KafkaTopics.PAYMENT_SUCCESSFUL, event.orderId().toString(), event);
    }

    public void publishFailed(PaymentFailedEvent event) {
        outboxEventService.enqueue(KafkaTopics.PAYMENT_FAILED, event.orderId().toString(), event);
    }

    public void publishNotificationRequested(NotificationRequestedEvent event) {
        outboxEventService.enqueue(KafkaTopics.NOTIFICATION_REQUESTED, event.orderId().toString(), event);
    }

    public void publishRefundInitiated(RefundInitiatedEvent event) {
        outboxEventService.enqueue(KafkaTopics.REFUND_INITIATED, event.orderId().toString(), event);
    }
}
