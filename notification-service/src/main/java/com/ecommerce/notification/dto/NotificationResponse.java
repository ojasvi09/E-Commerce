package com.ecommerce.notification.dto;

import com.ecommerce.notification.entity.NotificationStatus;
import com.ecommerce.notification.entity.NotificationType;

public record NotificationResponse(
        Long id,
        Long userId,
        String message,
        NotificationType type,
        NotificationStatus status
) {
}
