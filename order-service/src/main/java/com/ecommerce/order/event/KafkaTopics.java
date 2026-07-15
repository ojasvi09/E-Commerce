package com.ecommerce.order.event;

/** Central place for topic name constants this service produces/consumes. */
public final class KafkaTopics {

    public static final String ORDER_CREATED = "order.created";
    public static final String INVENTORY_FAILED = "inventory.failed";
    public static final String PAYMENT_SUCCESSFUL = "payment.successful";
    public static final String PAYMENT_FAILED = "payment.failed";

    private KafkaTopics() {
    }
}
