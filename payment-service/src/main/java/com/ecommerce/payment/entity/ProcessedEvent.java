package com.ecommerce.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per event this service has successfully finished processing (Phase 8,
 * idempotency). Keyed by the event's own eventId rather than Kafka topic/partition/offset,
 * so it also covers the Phase 6 outbox poller's double-send edge case (a resent outbox row
 * gets a NEW offset but the SAME eventId, since eventId is embedded in the payload, not
 * derived from the Kafka record). A listener checks existsById(event.eventId()) before
 * doing its work and inserts this row in the same transaction once the work succeeds —
 * see ARCHITECTURE.md's "Idempotency" section.
 */
@Entity
@Table(name = "processed_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    private UUID eventId;

    @Column(nullable = false)
    private String listenerName;

    @Column(nullable = false)
    private Instant processedAt;
}
