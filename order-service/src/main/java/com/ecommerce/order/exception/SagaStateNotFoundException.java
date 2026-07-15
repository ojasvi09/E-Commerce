package com.ecommerce.order.exception;

public class SagaStateNotFoundException extends RuntimeException {
    public SagaStateNotFoundException(Long orderId) {
        super("No saga state found for orderId: " + orderId);
    }
}
