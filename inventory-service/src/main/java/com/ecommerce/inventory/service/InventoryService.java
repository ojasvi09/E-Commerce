package com.ecommerce.inventory.service;

import com.ecommerce.inventory.dto.InventoryRequest;
import com.ecommerce.inventory.dto.InventoryResponse;
import com.ecommerce.inventory.dto.StockChangeRequest;
import com.ecommerce.inventory.entity.Inventory;
import com.ecommerce.inventory.exception.InsufficientStockException;
import com.ecommerce.inventory.exception.InventoryAlreadyExistsException;
import com.ecommerce.inventory.exception.InventoryNotFoundException;
import com.ecommerce.inventory.exception.InventoryNotFoundForProductException;
import com.ecommerce.inventory.repository.InventoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryService {

    private final InventoryRepository inventoryRepository;

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

    private Inventory getOrThrow(Long id) {
        return inventoryRepository.findById(id).orElseThrow(() -> new InventoryNotFoundException(id));
    }

    private InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(inventory.getId(), inventory.getProductId(), inventory.getQuantity());
    }
}
