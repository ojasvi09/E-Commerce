package com.ecommerce.order.service;

import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.dto.OrderItemResponse;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.OrderCreatedEvent;
import com.ecommerce.order.event.OrderEventProducer;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderEventProducer orderEventProducer;

    /**
     * Persists the order as CREATED and publishes OrderCreatedEvent, then returns
     * immediately — this is now asynchronous (Phase 3). Inventory/Payment consume the
     * event downstream and the order transitions to CONFIRMED or CANCELLED later, via
     * the Kafka listeners in this class. Callers must poll GET /api/orders/{id} to see
     * the final outcome; the HTTP response here is not the final word.
     */
    public OrderResponse create(OrderRequest request) {
        Order order = buildOrder(request);
        Order saved = orderRepository.save(order);

        List<OrderCreatedEvent.Item> items = request.items().stream()
                .map(i -> new OrderCreatedEvent.Item(i.productId(), i.quantity(), i.price()))
                .toList();
        orderEventProducer.publishOrderCreated(
                new OrderCreatedEvent(saved.getId(), saved.getUserId(), saved.getTotalAmount(), items));

        return toResponse(saved);
    }

    /** Called by PaymentSuccessfulListener once payment for this order has succeeded. */
    public void markConfirmed(Long orderId) {
        Order order = getOrThrow(orderId);
        order.setStatus(OrderStatus.CONFIRMED);
        log.info("Order {} confirmed", orderId);
    }

    /** Called by InventoryFailedListener/PaymentFailedListener when fulfillment fails downstream. */
    public void markCancelled(Long orderId, String reason) {
        Order order = getOrThrow(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        log.info("Order {} cancelled: {}", orderId, reason);
    }

    private Order buildOrder(OrderRequest request) {
        Order order = Order.builder()
                .userId(request.userId())
                .status(OrderStatus.CREATED)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.items()) {
            OrderItem item = OrderItem.builder()
                    .productId(itemRequest.productId())
                    .quantity(itemRequest.quantity())
                    .price(itemRequest.price())
                    .build();
            order.addItem(item);
            total = total.add(itemRequest.price().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }
        order.setTotalAmount(total);
        return order;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public OrderResponse update(Long id, OrderRequest request) {
        Order order = getOrThrow(id);
        order.setUserId(request.userId());
        order.getItems().clear();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.items()) {
            OrderItem item = OrderItem.builder()
                    .productId(itemRequest.productId())
                    .quantity(itemRequest.quantity())
                    .price(itemRequest.price())
                    .build();
            order.addItem(item);
            total = total.add(itemRequest.price().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }
        order.setTotalAmount(total);

        return toResponse(order);
    }

    public void delete(Long id) {
        orderRepository.delete(getOrThrow(id));
    }

    private Order getOrThrow(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(i -> new OrderItemResponse(i.getId(), i.getProductId(), i.getQuantity(), i.getPrice()))
                .toList();
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(), items);
    }
}
