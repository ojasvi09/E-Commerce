package com.ecommerce.product.exception;

public class SkuAlreadyExistsException extends RuntimeException {
    public SkuAlreadyExistsException(String sku) {
        super("SKU already in use: " + sku);
    }
}
