package com.ecommerce.payment.event;

import java.math.BigDecimal;

public record PaymentSuccessfulEvent(Long orderId, Long userId, BigDecimal amount) {
}
