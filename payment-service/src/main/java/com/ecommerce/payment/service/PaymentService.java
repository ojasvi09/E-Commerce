package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.PaymentRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.entity.Payment;
import com.ecommerce.payment.entity.PaymentStatus;
import com.ecommerce.payment.entity.ProcessedEvent;
import com.ecommerce.payment.event.PaymentEventProducer;
import com.ecommerce.payment.event.PaymentSuccessfulEvent;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer eventProducer;
    private final ProcessedEventRepository processedEventRepository;

    public PaymentResponse create(PaymentRequest request) {
        Payment payment = Payment.builder()
                .orderId(request.orderId())
                .amount(request.amount())
                .status(request.status())
                .build();
        return toResponse(paymentRepository.save(payment));
    }

    /**
     * Charges the order and enqueues PaymentSuccessfulEvent in the SAME database
     * transaction as the Payment save (Phase 6, transactional outbox) — one committed
     * unit. Throws if the charge itself fails; the caller (InventoryReservedEventListener)
     * enqueues PaymentFailedEvent in that case, as its own separate transaction — see the
     * listener's javadoc for why a shared @Transactional across both branches doesn't
     * work (this service's own create() throwing would otherwise poison the
     * failure-outcome outbox write too).
     *
     * <p>Phase 8 (idempotency): incomingEventId identifies the INCOMING
     * InventoryReservedEvent (not the outgoing PaymentSuccessfulEvent, which gets a fresh
     * random id every call) — if this exact incoming event was already processed, skip the
     * charge and outbox publish entirely, so a redelivered/retried InventoryReservedEvent
     * can't double-charge the order. Returns true if the work actually ran, false if skipped.
     */
    public boolean chargeAndPublish(UUID incomingEventId, Long orderId, Long userId, BigDecimal amount) {
        if (processedEventRepository.existsById(incomingEventId)) {
            log.info("Skipping already-processed InventoryReservedEvent {}", incomingEventId);
            return false;
        }
        create(new PaymentRequest(orderId, amount, PaymentStatus.SUCCESSFUL));
        eventProducer.publishSuccessful(new PaymentSuccessfulEvent(UUID.randomUUID(), orderId, userId, amount));
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(incomingEventId)
                .listenerName("InventoryReservedEventListener.onInventoryReserved")
                .processedAt(Instant.now())
                .build());
        return true;
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> findAll() {
        return paymentRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId).map(this::toResponse);
    }

    public PaymentResponse update(Long id, PaymentRequest request) {
        Payment payment = getOrThrow(id);
        payment.setOrderId(request.orderId());
        payment.setAmount(request.amount());
        payment.setStatus(request.status());
        return toResponse(payment);
    }

    public void delete(Long id) {
        paymentRepository.delete(getOrThrow(id));
    }

    private Payment getOrThrow(Long id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus());
    }
}
