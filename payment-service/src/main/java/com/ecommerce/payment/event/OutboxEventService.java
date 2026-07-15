package com.ecommerce.payment.event;

import com.ecommerce.payment.entity.OutboxEvent;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Enqueues an outbox row for later publish by OutboxPoller. Callers invoke this from
 * within the same @Transactional method/call chain that performed the Payment write the
 * event describes, so both writes commit or roll back together.
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
