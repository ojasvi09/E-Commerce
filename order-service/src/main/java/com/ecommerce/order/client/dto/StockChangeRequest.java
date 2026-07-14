package com.ecommerce.order.client.dto;

/**
 * Duplicated deliberately (not shared with inventory-service) to keep services
 * loosely coupled per the project's cross-cutting rule: prefer a small
 * duplicated DTO over a shared domain model across service boundaries.
 */
public record StockChangeRequest(Long productId, Integer quantity) {
}
