package com.ecommerce.payment.dto;

import com.ecommerce.payment.entity.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull(message = "orderId is required")
        Long orderId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = true, message = "amount must not be negative")
        BigDecimal amount,

        @NotNull(message = "status is required")
        PaymentStatus status
) {
}
