package com.ecommerce.inventory.config;

import org.apache.kafka.common.errors.SerializationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Phase 7 (Retry & Dead Letter Queue): every @KafkaListener in this service gets the same
 * retry-then-dead-letter policy instead of Spring Kafka's default (retry the same message
 * forever, blocking the partition). ExponentialBackOff: 1s initial delay, x2 multiplier,
 * 3 attempts total — the initial try plus maxAttempts(2) retries (~1s then ~2s later) —
 * before the message is dead-lettered. Note Spring's BackOff#setMaxAttempts counts RETRIES,
 * not total attempts, so maxAttempts(2) here means 3 total (this tripped us up during
 * testing: maxAttempts(3) actually produced 4 total attempts).
 *
 * <p>DeadLetterPublishingRecoverer republishes an exhausted message to
 * "&lt;originalTopic&gt;.DLT" (its default naming convention) once retries are exhausted,
 * so the consumer can move on instead of being stuck. Deserialization failures (malformed
 * JSON, wrong shape) are classified as non-retryable — retrying the same unparseable bytes
 * can never succeed, so those go straight to the DLQ without wasting the backoff delay.
 * Everything else (e.g. a transient DB error inside a listener) retries normally.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(2);

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(SerializationException.class, DeserializationException.class);
        return errorHandler;
    }
}
