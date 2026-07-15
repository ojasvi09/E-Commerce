package com.ecommerce.order.event;

public record PaymentFailedEvent(Long orderId, Long userId, String reason) {
}
