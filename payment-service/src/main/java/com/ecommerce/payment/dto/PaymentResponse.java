package com.ecommerce.payment.dto;

import com.ecommerce.payment.entity.PaymentStatus;
import java.math.BigDecimal;

public record PaymentResponse(
        Long id,
        Long orderId,
        BigDecimal amount,
        PaymentStatus status
) {
}
