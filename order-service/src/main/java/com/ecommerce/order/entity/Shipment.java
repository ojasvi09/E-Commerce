package com.ecommerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Minimal fulfillment record created once an order is confirmed (payment succeeded).
 * Lives in Order Service rather than a dedicated Shipment microservice — Phase 1 fixed
 * the service list at 7 and a full shipment/carrier-tracking domain is out of scope for
 * Phase 4, which only needs ShipmentCreatedEvent to exist and fire.
 */
@Entity
@Table(name = "shipments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
