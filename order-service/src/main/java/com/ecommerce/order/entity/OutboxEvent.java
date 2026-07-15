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
 * One row per domain event awaiting publish to Kafka (Phase 6, transactional outbox).
 * Written in the SAME database transaction as the domain entity change that caused it
 * (e.g. saving the Order alongside its OrderCreatedEvent row), so the write and the
 * "intent to publish" either both commit or both roll back — unlike Phase 3-5, where
 * kafkaTemplate.send() happened after the transaction with no such guarantee.
 * OutboxPoller reads unpublished rows and performs the actual Kafka send asynchronously.
 */
@Entity
@Table(name = "outbox_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;
}
