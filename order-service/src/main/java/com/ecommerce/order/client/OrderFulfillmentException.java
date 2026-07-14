package com.ecommerce.order.client;

/** Raised when inventory reservation or payment charging fails after retries/circuit breaker. */
public class OrderFulfillmentException extends RuntimeException {
    public OrderFulfillmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
