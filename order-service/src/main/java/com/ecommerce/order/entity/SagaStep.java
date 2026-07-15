package com.ecommerce.order.entity;

/**
 * Named steps of the Order->Inventory->Payment->Shipment saga (Phase 5), as observed from
 * order-service's own vantage point. This formalizes the choreography already implemented
 * in Phase 4 (each service still decides its own compensating action independently — there
 * is no orchestrator) by giving the sequence a single queryable state per order, instead of
 * only being inferable by cross-referencing Order/Inventory/Payment records separately.
 *
 * <p>There is no INVENTORY_RESERVED step: order-service never consumes a positive
 * "inventory reserved" event (only inventory.failed, on the failure path), so it can't
 * observe that transition — inventory-service's own state is the source of truth for it.
 */
public enum SagaStep {
    STARTED,
    PAYMENT_PROCESSED,
    SHIPMENT_CREATED,
    COMPENSATING,
    COMPLETED,
    FAILED
}
