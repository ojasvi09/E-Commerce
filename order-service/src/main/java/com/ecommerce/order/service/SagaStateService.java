package com.ecommerce.order.service;

import com.ecommerce.order.dto.SagaStateResponse;
import com.ecommerce.order.entity.SagaState;
import com.ecommerce.order.entity.SagaStep;
import com.ecommerce.order.exception.SagaStateNotFoundException;
import com.ecommerce.order.repository.SagaStateRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the saga's current step per order (Phase 5). This is purely an observability/
 * query layer on top of the choreography already implemented in Phase 4 — each service
 * still decides and performs its own compensating action independently; this class does
 * not sequence or coordinate anything, it only records what step OrderService/
 * OrderEventListener have observed so far.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SagaStateService {

    private static final Logger log = LoggerFactory.getLogger(SagaStateService.class);

    private final SagaStateRepository sagaStateRepository;

    public void start(Long orderId) {
        advance(orderId, SagaStep.STARTED, null);
    }

    public void advance(Long orderId, SagaStep step, String reason) {
        SagaState state = sagaStateRepository.findById(orderId)
                .orElseGet(() -> SagaState.builder().orderId(orderId).build());
        state.setCurrentStep(step);
        state.setReason(reason);
        state.setUpdatedAt(Instant.now());
        sagaStateRepository.save(state);
        log.info("Saga for orderId {} -> {}{}", orderId, step, reason != null ? " (" + reason + ")" : "");
    }

    @Transactional(readOnly = true)
    public SagaStateResponse findByOrderId(Long orderId) {
        SagaState state = sagaStateRepository.findById(orderId)
                .orElseThrow(() -> new SagaStateNotFoundException(orderId));
        return new SagaStateResponse(state.getOrderId(), state.getCurrentStep(), state.getReason(), state.getUpdatedAt());
    }
}
