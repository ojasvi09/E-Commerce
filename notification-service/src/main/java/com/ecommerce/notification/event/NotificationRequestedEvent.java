package com.ecommerce.notification.event;

import java.util.UUID;

/** eventId (Phase 8): used as the dedupe key into processed_events — see ARCHITECTURE.md. */
public record NotificationRequestedEvent(
        UUID eventId,
        Long orderId,
        Long userId,
        String message
) {
}
