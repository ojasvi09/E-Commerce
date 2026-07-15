package com.ecommerce.order.event;

import java.math.BigDecimal;

public record PaymentSuccessfulEvent(Long orderId, Long userId, BigDecimal amount) {
}
