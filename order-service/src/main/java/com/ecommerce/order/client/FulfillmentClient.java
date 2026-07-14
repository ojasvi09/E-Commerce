package com.ecommerce.order.client;

import com.ecommerce.order.client.dto.InventoryResponse;
import com.ecommerce.order.client.dto.PaymentRequest;
import com.ecommerce.order.client.dto.PaymentResponse;
import com.ecommerce.order.client.dto.StockChangeRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thin resilience wrapper around the Inventory/Payment Feign clients. Kept separate from
 * OrderService so retry/circuit-breaker annotations (which rely on Spring AOP proxying)
 * apply cleanly on public method boundaries.
 */
@Component
@RequiredArgsConstructor
public class FulfillmentClient {

    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory", fallbackMethod = "reserveFallback")
    public InventoryResponse reserveStock(Long productId, Integer quantity) {
        return inventoryClient.reserve(new StockChangeRequest(productId, quantity));
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory", fallbackMethod = "releaseFallback")
    public void releaseStock(Long productId, Integer quantity) {
        inventoryClient.release(new StockChangeRequest(productId, quantity));
    }

    @Retry(name = "payment")
    @CircuitBreaker(name = "payment", fallbackMethod = "chargeFallback")
    public PaymentResponse charge(Long orderId, java.math.BigDecimal amount) {
        return paymentClient.charge(new PaymentRequest(orderId, amount, "SUCCESSFUL"));
    }

    private InventoryResponse reserveFallback(Long productId, Integer quantity, Throwable t) {
        throw new OrderFulfillmentException("Inventory reservation failed for productId " + productId, t);
    }

    private void releaseFallback(Long productId, Integer quantity, Throwable t) {
        // Best-effort compensation: if release also fails, surface it via logs only so the
        // original failure (which triggered this rollback) remains the primary error.
        throw new OrderFulfillmentException("Inventory release failed for productId " + productId, t);
    }

    private PaymentResponse chargeFallback(Long orderId, java.math.BigDecimal amount, Throwable t) {
        throw new OrderFulfillmentException("Payment failed for orderId " + orderId, t);
    }
}
