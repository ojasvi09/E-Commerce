package com.ecommerce.inventory.dto;

public record InventoryResponse(
        Long id,
        Long productId,
        Integer quantity
) {
}
