package com.ecommerce.order.client.dto;

import java.math.BigDecimal;

public record PaymentRequest(Long orderId, BigDecimal amount, String status) {
}
