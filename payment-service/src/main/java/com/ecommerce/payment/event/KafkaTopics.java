package com.ecommerce.payment.event;

public final class KafkaTopics {

    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String PAYMENT_SUCCESSFUL = "payment.successful";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String NOTIFICATION_REQUESTED = "notification.requested";
    public static final String REFUND_INITIATED = "refund.initiated";

    private KafkaTopics() {
    }
}
