package com.ecommerce.order.event;

import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Enqueues an outbox row for later publish by OutboxPoller. Callers invoke this from
 * within the same @Transactional method that saves the domain entity the event
 * describes, so both writes commit or roll back together (see OutboxEvent's javadoc).
 * Never call KafkaTemplate directly from a producer for a Kafka-topic event any more —
 * this class is the only path onto the outbox table, and the outbox table is the only
 * path onto Kafka, for this service's own topics.
 */
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void enqueue(String topic, String eventKey, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.builder()
                    .topic(topic)
                    .eventKey(eventKey)
                    .payload(payload)
                    .createdAt(Instant.now())
                    .build());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize event for outbox: " + event, ex);
        }
    }
}
