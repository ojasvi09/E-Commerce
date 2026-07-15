package com.ecommerce.payment.event;

public record OrderCancelledEvent(
        Long orderId,
        Long userId,
        String reason
) {
}
