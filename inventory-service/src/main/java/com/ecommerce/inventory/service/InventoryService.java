package com.ecommerce.inventory.service;

import com.ecommerce.inventory.dto.InventoryRequest;
import com.ecommerce.inventory.dto.InventoryResponse;
import com.ecommerce.inventory.dto.StockChangeRequest;
import com.ecommerce.inventory.entity.Inventory;
import com.ecommerce.inventory.entity.ProcessedEvent;
import com.ecommerce.inventory.event.InventoryEventProducer;
import com.ecommerce.inventory.event.InventoryReservedEvent;
import com.ecommerce.inventory.exception.InsufficientStockException;
import com.ecommerce.inventory.exception.InventoryAlreadyExistsException;
import com.ecommerce.inventory.exception.InventoryNotFoundException;
import com.ecommerce.inventory.exception.InventoryNotFoundForProductException;
import com.ecommerce.inventory.repository.InventoryRepository;
import com.ecommerce.inventory.repository.ProcessedEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryEventProducer eventProducer;
    private final ProcessedEventRepository processedEventRepository;

    public InventoryResponse create(InventoryRequest request) {
        if (inventoryRepository.existsByProductId(request.productId())) {
            throw new InventoryAlreadyExistsException(request.productId());
        }
        Inventory inventory = Inventory.builder()
                .productId(request.productId())
                .quantity(request.quantity())
                .build();
        return toResponse(inventoryRepository.save(inventory));
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> findAll() {
        return inventoryRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public InventoryResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public InventoryResponse update(Long id, InventoryRequest request) {
        Inventory inventory = getOrThrow(id);
        if (!inventory.getProductId().equals(request.productId())
                && inventoryRepository.existsByProductId(request.productId())) {
            throw new InventoryAlreadyExistsException(request.productId());
        }
        inventory.setProductId(request.productId());
        inventory.setQuantity(request.quantity());
        return toResponse(inventory);
    }

    public void delete(Long id) {
        inventoryRepository.delete(getOrThrow(id));
    }

    /**
     * Decrements stock for a product. Uses a pessimistic write lock so concurrent
     * reservations for the same product can't both read a stale quantity and
     * oversell (classic lost-update race condition).
     */
    public InventoryResponse reserve(StockChangeRequest request) {
        Inventory inventory = inventoryRepository.findWithLockByProductId(request.productId())
                .orElseThrow(() -> new InventoryNotFoundForProductException(request.productId()));
        if (inventory.getQuantity() < request.quantity()) {
            throw new InsufficientStockException(request.productId(), inventory.getQuantity(), request.quantity());
        }
        inventory.setQuantity(inventory.getQuantity() - request.quantity());
        return toResponse(inventory);
    }

    /** Compensating action: adds stock back (e.g. when an order is cancelled after reservation). */
    public InventoryResponse release(StockChangeRequest request) {
        Inventory inventory = inventoryRepository.findWithLockByProductId(request.productId())
                .orElseThrow(() -> new InventoryNotFoundForProductException(request.productId()));
        inventory.setQuantity(inventory.getQuantity() + request.quantity());
        return toResponse(inventory);
    }

    /**
     * Reserves stock for every item in one call, all-or-nothing: if any item can't be
     * reserved (not found or insufficient stock), every item already reserved in this
     * call is released before the exception propagates, so a partially-fulfilled order
     * never leaves inventory partially decremented. Used by the OrderCreatedEvent
     * listener (Phase 3) — the same all-or-nothing guarantee Order Service used to
     * provide itself via try/catch in Phase 2, now living where the reservation happens.
     */
    public void reserveAll(List<StockChangeRequest> items) {
        List<StockChangeRequest> reserved = new ArrayList<>();
        try {
            for (StockChangeRequest item : items) {
                reserve(item);
                reserved.add(item);
            }
        } catch (RuntimeException ex) {
            for (StockChangeRequest item : reserved) {
                release(item);
            }
            throw ex;
        }
    }

    /**
     * Reserves stock for every item, then enqueues InventoryReservedEvent in the SAME
     * database transaction as the stock decrement (Phase 6, transactional outbox) — one
     * committed unit, so a crash between them can't leave one without the other. Throws
     * if reservation fails; the caller (OrderCreatedEventListener) is responsible for
     * enqueueing InventoryFailedEvent in that case, as its own separate transaction — see
     * the listener's javadoc for why that split matters (a shared @Transactional across
     * both branches would let reserveAll()'s failure poison the failure-outcome outbox
     * write too).
     *
     * <p>Phase 8 (idempotency): incomingEventId identifies the INCOMING OrderCreatedEvent
     * (not the outgoing InventoryReservedEvent, which gets a fresh random id every call and
     * would never repeat) — if this exact incoming event was already processed, skip the
     * reservation and outbox publish entirely, so a redelivered/retried OrderCreatedEvent
     * can't double-decrement stock. Returns true if the work actually ran, false if skipped.
     */
    public boolean reserveAllAndPublish(UUID incomingEventId, List<StockChangeRequest> items,
            InventoryReservedEvent reservedEvent) {
        if (processedEventRepository.existsById(incomingEventId)) {
            log.info("Skipping already-processed OrderCreatedEvent {}", incomingEventId);
            return false;
        }
        reserveAll(items);
        eventProducer.publishReserved(reservedEvent);
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(incomingEventId)
                .listenerName("OrderCreatedEventListener.onOrderCreated")
                .processedAt(Instant.now())
                .build());
        return true;
    }

    /**
     * Compensating release for a whole order (Phase 8, idempotency) — guards on the
     * incoming OrderCancelledEvent's own id so a redelivered/retried cancellation can't
     * double-release stock. See reserveAllAndPublish's javadoc for why the guard key is
     * the incoming event's id, not anything derived per-item.
     */
    public boolean releaseAllForOrder(UUID incomingEventId, List<StockChangeRequest> items) {
        if (processedEventRepository.existsById(incomingEventId)) {
            log.info("Skipping already-processed OrderCancelledEvent {}", incomingEventId);
            return false;
        }
        for (StockChangeRequest item : items) {
            release(item);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(incomingEventId)
                .listenerName("OrderCreatedEventListener.onOrderCancelled")
                .processedAt(Instant.now())
                .build());
        return true;
    }

    private Inventory getOrThrow(Long id) {
        return inventoryRepository.findById(id).orElseThrow(() -> new InventoryNotFoundException(id));
    }

    private InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(inventory.getId(), inventory.getProductId(), inventory.getQuantity());
    }
}
