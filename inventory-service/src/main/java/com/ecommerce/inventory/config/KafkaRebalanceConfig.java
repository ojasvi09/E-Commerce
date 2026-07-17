package com.ecommerce.inventory.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

import java.util.Collection;

/**
 * Phase 9 (Ordering &amp; Scaling): logs partition assignment/revocation for every
 * {@code @KafkaListener} container in this service, so running a second instance in the
 * same consumer group ({@code inventory-service}) makes Kafka's rebalancing visible in
 * the logs instead of being an invisible internal detail. Purely observational — does not
 * change delivery semantics or ordering, which already comes from every producer keying
 * messages by {@code orderId} (see KafkaTopicConfig and each *EventProducer).
 */
@Slf4j
@Configuration
public class KafkaRebalanceConfig {

    @Bean
    public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> rebalanceLoggingCustomizer() {
        return container -> container.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                log.info("Rebalance: partitions ASSIGNED to this instance: {}", partitions);
            }

            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                log.info("Rebalance: partitions REVOKED from this instance: {}", partitions);
            }
        });
    }
}
