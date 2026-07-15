package com.ecommerce.payment.event;

public record PaymentFailedEvent(Long orderId, Long userId, String reason) {
}
