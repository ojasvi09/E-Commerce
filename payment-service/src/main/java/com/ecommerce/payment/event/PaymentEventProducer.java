package com.ecommerce.payment.event;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishSuccessful(PaymentSuccessfulEvent event) {
        kafkaTemplate.send(KafkaTopics.PAYMENT_SUCCESSFUL, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentSuccessfulEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published PaymentSuccessfulEvent for orderId {}", event.orderId());
                    }
                });
    }

    public void publishFailed(PaymentFailedEvent event) {
        kafkaTemplate.send(KafkaTopics.PAYMENT_FAILED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentFailedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published PaymentFailedEvent for orderId {}", event.orderId());
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

    public void publishRefundInitiated(RefundInitiatedEvent event) {
        kafkaTemplate.send(KafkaTopics.REFUND_INITIATED, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RefundInitiatedEvent for orderId {}", event.orderId(), ex);
                    } else {
                        log.info("Published RefundInitiatedEvent for orderId {}", event.orderId());
                    }
                });
    }
}
