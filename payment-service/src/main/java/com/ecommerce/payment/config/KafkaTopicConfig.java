package com.ecommerce.payment.config;

import com.ecommerce.payment.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the topics this service owns/produces: payment.successful and payment.failed. */
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
}
