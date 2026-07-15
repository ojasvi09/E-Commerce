package com.ecommerce.payment.event;

import com.ecommerce.payment.entity.OutboxEvent;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls outbox_events for unpublished rows and sends each to Kafka, marking it published
 * on success. See order-service's OutboxPoller for the full rationale (payload is parsed
 * back into a plain Map, not the original event record type — consumers deserialize
 * purely from JSON shape, ignoring type headers, so this is wire-compatible).
 */
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByIdAsc();
        for (OutboxEvent event : pending) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            Map<String, Object> value = objectMapper.readValue(event.getPayload(), Map.class);
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), value).get();
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
            log.info("Outbox published id={} topic={} key={}", event.getId(), event.getTopic(), event.getEventKey());
        } catch (Exception ex) {
            log.error("Outbox publish failed id={} topic={} key={}, will retry next poll",
                    event.getId(), event.getTopic(), event.getEventKey(), ex);
        }
    }
}
