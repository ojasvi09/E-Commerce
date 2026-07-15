package com.ecommerce.notification.event;

import com.ecommerce.notification.dto.NotificationRequest;
import com.ecommerce.notification.entity.NotificationStatus;
import com.ecommerce.notification.entity.NotificationType;
import com.ecommerce.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens for the final outcome of an order (payment succeeded/failed, or inventory
 * failed before payment was even attempted) and creates a notification record for the
 * user. This is the end of the Phase 3 event chain: Order -> Inventory -> Payment ->
 * Notification. Delivery itself (actually sending an email/SMS) is still not
 * implemented — same as Phase 1/2, this only persists the notification's intent.
 */
@Component
@RequiredArgsConstructor
public class OrderOutcomeEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderOutcomeEventListener.class);

    private final NotificationService notificationService;

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_SUCCESSFUL,
            groupId = "notification-service",
            properties = "spring.json.value.default.type=com.ecommerce.notification.event.PaymentSuccessfulEvent")
    public void onPaymentSuccessful(PaymentSuccessfulEvent event) {
        log.info("Received PaymentSuccessfulEvent for orderId {}, notifying userId {}",
                event.orderId(), event.userId());
        notificationService.create(new NotificationRequest(
                event.userId(),
                "Your order #" + event.orderId() + " has been confirmed. Amount charged: " + event.amount(),
                NotificationType.EMAIL,
                NotificationStatus.PENDING));
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "notification-service",
            properties = "spring.json.value.default.type=com.ecommerce.notification.event.PaymentFailedEvent")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent for orderId {}, notifying userId {}",
                event.orderId(), event.userId());
        notificationService.create(new NotificationRequest(
                event.userId(),
                "Your order #" + event.orderId() + " was cancelled: payment failed (" + event.reason() + ")",
                NotificationType.EMAIL,
                NotificationStatus.PENDING));
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_FAILED,
            groupId = "notification-service",
            properties = "spring.json.value.default.type=com.ecommerce.notification.event.InventoryFailedEvent")
    public void onInventoryFailed(InventoryFailedEvent event) {
        log.info("Received InventoryFailedEvent for orderId {}, notifying userId {}",
                event.orderId(), event.userId());
        notificationService.create(new NotificationRequest(
                event.userId(),
                "Your order #" + event.orderId() + " was cancelled: item(s) out of stock (" + event.reason() + ")",
                NotificationType.EMAIL,
                NotificationStatus.PENDING));
    }
}
