package com.ecommerce.payment.event;

import java.math.BigDecimal;

public record InventoryReservedEvent(Long orderId, Long userId, BigDecimal totalAmount) {
}
