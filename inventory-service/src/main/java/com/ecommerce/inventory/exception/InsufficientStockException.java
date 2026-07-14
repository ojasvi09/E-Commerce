package com.ecommerce.inventory.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long productId, Integer available, Integer requested) {
        super("Insufficient stock for productId " + productId + ": available=" + available + ", requested=" + requested);
    }
}
