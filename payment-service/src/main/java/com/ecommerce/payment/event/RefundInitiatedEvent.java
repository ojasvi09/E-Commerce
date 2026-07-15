package com.ecommerce.payment.event;

import java.math.BigDecimal;

public record RefundInitiatedEvent(
        Long orderId,
        Long userId,
        BigDecimal amount,
        String reason
) {
}
