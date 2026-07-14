package com.ecommerce.notification.dto;

import com.ecommerce.notification.entity.NotificationStatus;
import com.ecommerce.notification.entity.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NotificationRequest(
        @NotNull(message = "userId is required")
        Long userId,

        @NotBlank(message = "message is required")
        String message,

        @NotNull(message = "type is required")
        NotificationType type,

        @NotNull(message = "status is required")
        NotificationStatus status
) {
}
