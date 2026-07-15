package com.ecommerce.order.config;

import com.ecommerce.order.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic this service owns (order.created) so it exists with the right
 * partition count even on a fresh Kafka broker. Spring Boot auto-creates any NewTopic
 * bean on startup via KafkaAdmin. This service doesn't own inventory.failed/payment.*
 * (those are declared by inventory-service/payment-service, the services that produce
 * them) — it only consumes them.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CREATED).partitions(3).replicas(1).build();
    }
}
