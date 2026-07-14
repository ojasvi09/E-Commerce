package com.ecommerce.order.client;

import com.ecommerce.order.client.dto.PaymentRequest;
import com.ecommerce.order.client.dto.PaymentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service", url = "${services.payment.url}")
public interface PaymentClient {

    @PostMapping("/api/payments")
    PaymentResponse charge(@RequestBody PaymentRequest request);
}
