package com.ecommerce.inventory.event;

public final class KafkaTopics {

    public static final String ORDER_CREATED = "order.created";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_FAILED = "inventory.failed";

    private KafkaTopics() {
    }
}
