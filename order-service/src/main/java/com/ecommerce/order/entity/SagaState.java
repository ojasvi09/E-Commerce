package com.ecommerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per order, tracking the saga's current step and the reason for its most recent
 * transition (populated on COMPENSATING/FAILED, null otherwise). Lives in order-service
 * since order-service is already the saga's initiator (publishes OrderCreatedEvent) and
 * finisher (consumes every downstream outcome event) — no new service needed for Phase 5's
 * choreography-only scope.
 */
@Entity
@Table(name = "saga_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SagaState {

    @Id
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStep currentStep;

    private String reason;

    @Column(nullable = false)
    private Instant updatedAt;
}
