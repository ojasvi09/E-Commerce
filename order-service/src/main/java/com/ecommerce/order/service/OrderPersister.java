package com.ecommerce.order.service;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a cancelled order in its own new transaction, separate from OrderService's
 * outer transaction. Needed because the outer @Transactional method throws/handles a
 * fulfillment failure and must not have that failure roll back the CANCELLED save too.
 * Self-invocation from within OrderService wouldn't honor REQUIRES_NEW (Spring's proxy-based
 * AOP only intercepts calls through the bean, not this-calls), hence this separate bean.
 */
@Component
@RequiredArgsConstructor
public class OrderPersister {

    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order saveInNewTransaction(Order order) {
        return orderRepository.save(order);
    }
}
