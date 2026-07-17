package com.ecommerce.notification.service;

import com.ecommerce.notification.dto.NotificationRequest;
import com.ecommerce.notification.dto.NotificationResponse;
import com.ecommerce.notification.entity.Notification;
import com.ecommerce.notification.entity.ProcessedEvent;
import com.ecommerce.notification.exception.NotificationNotFoundException;
import com.ecommerce.notification.repository.NotificationRepository;
import com.ecommerce.notification.repository.ProcessedEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;

    public NotificationResponse create(NotificationRequest request) {
        Notification notification = Notification.builder()
                .userId(request.userId())
                .message(request.message())
                .type(request.type())
                .status(request.status())
                .build();
        return toResponse(notificationRepository.save(notification));
    }

    /**
     * Phase 8 (idempotency): creates a Notification only if the incoming
     * NotificationRequestedEvent hasn't already been processed — guards against a
     * redelivered/retried event sending a duplicate notification to the user. Returns
     * true if the notification was actually created, false if skipped.
     */
    public boolean createIfNotProcessed(UUID incomingEventId, NotificationRequest request) {
        if (processedEventRepository.existsById(incomingEventId)) {
            log.info("Skipping already-processed NotificationRequestedEvent {}", incomingEventId);
            return false;
        }
        create(request);
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(incomingEventId)
                .listenerName("NotificationRequestedEventListener.onNotificationRequested")
                .processedAt(Instant.now())
                .build());
        return true;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> findAll() {
        return notificationRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public NotificationResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public NotificationResponse update(Long id, NotificationRequest request) {
        Notification notification = getOrThrow(id);
        notification.setUserId(request.userId());
        notification.setMessage(request.message());
        notification.setType(request.type());
        notification.setStatus(request.status());
        return toResponse(notification);
    }

    public void delete(Long id) {
        notificationRepository.delete(getOrThrow(id));
    }

    private Notification getOrThrow(Long id) {
        return notificationRepository.findById(id).orElseThrow(() -> new NotificationNotFoundException(id));
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getUserId(), notification.getMessage(),
                notification.getType(), notification.getStatus());
    }
}
