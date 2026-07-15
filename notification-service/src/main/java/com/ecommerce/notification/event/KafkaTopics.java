package com.ecommerce.notification.event;

public final class KafkaTopics {

    public static final String INVENTORY_FAILED = "inventory.failed";
    public static final String PAYMENT_SUCCESSFUL = "payment.successful";
    public static final String PAYMENT_FAILED = "payment.failed";

    private KafkaTopics() {
    }
}
