package com.ecommerce.order.dto;

import com.ecommerce.order.entity.SagaStep;
import java.time.Instant;

public record SagaStateResponse(Long orderId, SagaStep currentStep, String reason, Instant updatedAt) {
}
