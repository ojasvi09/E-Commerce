package com.ecommerce.order.event;

public record ShipmentCreatedEvent(
        Long orderId,
        Long userId,
        Long shipmentId
) {
}
