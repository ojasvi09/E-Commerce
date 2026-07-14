package com.ecommerce.user.dto;

public record UserResponse(
        Long id,
        String name,
        String email
) {
}
