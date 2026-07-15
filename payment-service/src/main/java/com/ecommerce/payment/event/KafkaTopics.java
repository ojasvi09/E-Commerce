package com.ecommerce.payment.event;

public final class KafkaTopics {

    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String PAYMENT_SUCCESSFUL = "payment.successful";
    public static final String PAYMENT_FAILED = "payment.failed";

    private KafkaTopics() {
    }
}
