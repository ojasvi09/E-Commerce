package com.ecommerce.payment.config;

import com.ecommerce.payment.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service owns/produces: payment.successful, payment.failed,
 * refund.initiated, and notification.requested (shared topic — inventory-service also
 * produces onto it).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentSuccessfulTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_SUCCESSFUL).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_FAILED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic refundInitiatedTopic() {
        return TopicBuilder.name(KafkaTopics.REFUND_INITIATED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic notificationRequestedTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATION_REQUESTED).partitions(3).replicas(1).build();
    }
}
