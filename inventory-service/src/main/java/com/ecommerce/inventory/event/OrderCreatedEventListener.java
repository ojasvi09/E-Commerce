package com.ecommerce.inventory.event;

import com.ecommerce.inventory.dto.StockChangeRequest;
import com.ecommerce.inventory.service.InventoryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reacts to a newly created order by attempting to reserve stock for every line item.
 * Publishes InventoryReservedEvent on success, InventoryFailedEvent on any failure
 * (not found / insufficient stock) — Order Service listens for both to update the
 * order's status. This replaces the direct POST /api/inventory/reserve call
 * order-service made in Phase 2.
 *
 * <p>The listener method itself is intentionally NOT @Transactional. reserveAll() throwing
 * marks Spring's transaction rollback-only, which poisons the WHOLE transaction even
 * after the exception is caught — any outbox row written afterward in that same
 * transaction (e.g. InventoryFailedEvent) would silently never commit, so the consumer
 * would appear to succeed (no exception escapes onOrderCreated) while actually retrying
 * forever because the DB state never advances (this happened during Phase 6 development —
 * see InventoryService.reserveAllAndPublish's javadoc). InventoryService.reserveAllAndPublish
 * runs reserveAll + publishReserved as one atomic transaction on the success path; the
 * catch block below runs on a stock-reservation failure, which by then has already been
 * fully committed/rolled back by reserveAllAndPublish's own transaction, so
 * publishFailed/publishNotificationRequested (each its own transaction, since
 * OutboxEventService.enqueue isn't itself @Transactional here) are never at risk of being
 * silently discarded.
 */
@Component
@RequiredArgsConstructor
public class OrderCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedEventListener.class);

    private final InventoryService inventoryService;
    private final InventoryEventProducer eventProducer;

    @KafkaListener(
            topics = KafkaTopics.ORDER_CREATED,
            groupId = "inventory-service",
            properties = "spring.json.value.default.type=com.ecommerce.inventory.event.OrderCreatedEvent")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent for orderId {}, reserving {} item(s)",
                event.orderId(), event.items().size());
        List<StockChangeRequest> items = event.items().stream()
                .map(i -> new StockChangeRequest(i.productId(), i.quantity()))
                .toList();
        try {
            inventoryService.reserveAllAndPublish(event.eventId(), items,
                    new InventoryReservedEvent(UUID.randomUUID(), event.orderId(), event.userId(), event.totalAmount()));
        } catch (RuntimeException ex) {
            log.error("Inventory reservation failed for orderId {}", event.orderId(), ex);
            eventProducer.publishFailed(new InventoryFailedEvent(UUID.randomUUID(), event.orderId(), event.userId(), ex.getMessage()));
            eventProducer.publishNotificationRequested(new NotificationRequestedEvent(
                    UUID.randomUUID(), event.orderId(), event.userId(),
                    "Your order #" + event.orderId() + " was cancelled: item(s) out of stock (" + ex.getMessage() + ")"));
        }
    }

    /**
     * Compensating action: order-service only publishes OrderCancelledEvent when
     * inventory HAD been reserved (payment failed after the fact) — see
     * OrderService.markCancelled in order-service. It does not publish this event for
     * the inventory-reservation-itself-failed case, since reserveAll() already rolled
     * that back internally before InventoryFailedEvent was even sent.
     */
    @Transactional
    @KafkaListener(
            topics = KafkaTopics.ORDER_CANCELLED,
            groupId = "inventory-service",
            properties = "spring.json.value.default.type=com.ecommerce.inventory.event.OrderCancelledEvent")
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Received OrderCancelledEvent for orderId {}, releasing {} item(s)",
                event.orderId(), event.items().size());
        List<StockChangeRequest> released = event.items().stream()
                .map(i -> new StockChangeRequest(i.productId(), i.quantity()))
                .toList();
        inventoryService.releaseAllForOrder(event.eventId(), released);
    }
}
