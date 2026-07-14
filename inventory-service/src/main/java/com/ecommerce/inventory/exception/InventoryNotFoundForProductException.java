package com.ecommerce.inventory.exception;

public class InventoryNotFoundForProductException extends RuntimeException {
    public InventoryNotFoundForProductException(Long productId) {
        super("No inventory record found for productId: " + productId);
    }
}
