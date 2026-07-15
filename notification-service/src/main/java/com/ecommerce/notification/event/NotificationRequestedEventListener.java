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
 * Phase 4: this service no longer listens to raw domain events (payment.successful,
 * payment.failed, inventory.failed) directly — every producing service now builds its
 * own user-facing message and publishes a single NotificationRequestedEvent, so this
 * listener stays a dumb sink regardless of how many upstream services/reasons exist to
 * notify a user in the future.
 */
@Component
@RequiredArgsConstructor
public class NotificationRequestedEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationRequestedEventListener.class);

    private final NotificationService notificationService;

    @KafkaListener(topics = KafkaTopics.NOTIFICATION_REQUESTED, groupId = "notification-service")
    public void onNotificationRequested(NotificationRequestedEvent event) {
        log.info("Received NotificationRequestedEvent for orderId {}, notifying userId {}",
                event.orderId(), event.userId());
        notificationService.create(new NotificationRequest(
                event.userId(),
                event.message(),
                NotificationType.EMAIL,
                NotificationStatus.PENDING));
    }
}
