package com.ecommerce.order.service;

import com.ecommerce.order.client.FulfillmentClient;
import com.ecommerce.order.client.OrderFulfillmentException;
import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.dto.OrderItemResponse;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final FulfillmentClient fulfillmentClient;
    private final OrderPersister orderPersister;

    public OrderResponse create(OrderRequest request) {
        Order order = buildOrder(request);
        BigDecimal total = order.getTotalAmount();

        // Persist first (own transaction) so the order has an id before we call Payment
        // Service, which requires a non-null orderId. This also means the CREATED row
        // already exists if the fulfillment step below fails partway through.
        order = orderPersister.saveInNewTransaction(order);

        // Reserve stock for every line item first, then charge payment for the total.
        // If anything fails, release whatever was already reserved for this order
        // (compensating action) and persist the order as CANCELLED instead of CONFIRMED.
        List<OrderItemRequest> reserved = new ArrayList<>();
        try {
            for (OrderItemRequest itemRequest : request.items()) {
                fulfillmentClient.reserveStock(itemRequest.productId(), itemRequest.quantity());
                reserved.add(itemRequest);
            }
            fulfillmentClient.charge(order.getId(), total);
            order.setStatus(OrderStatus.CONFIRMED);
            return toResponse(orderPersister.saveInNewTransaction(order));
        } catch (OrderFulfillmentException ex) {
            log.error("Order fulfillment failed for orderId {}, releasing {} reserved item(s) and cancelling",
                    order.getId(), reserved.size(), ex);
            for (OrderItemRequest itemRequest : reserved) {
                try {
                    fulfillmentClient.releaseStock(itemRequest.productId(), itemRequest.quantity());
                } catch (OrderFulfillmentException releaseEx) {
                    log.error("Failed to release stock for productId {} during rollback",
                            itemRequest.productId(), releaseEx);
                }
            }
            order.setStatus(OrderStatus.CANCELLED);
            return toResponse(orderPersister.saveInNewTransaction(order));
        }
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
