package com.ecommerce.notification.event;

public record PaymentFailedEvent(Long orderId, Long userId, String reason) {
}
