package com.ecommerce.inventory.exception;

public class InventoryAlreadyExistsException extends RuntimeException {
    public InventoryAlreadyExistsException(Long productId) {
        super("Inventory record already exists for productId: " + productId);
    }
}
