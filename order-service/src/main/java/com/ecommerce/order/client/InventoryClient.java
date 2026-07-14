package com.ecommerce.order.client;

import com.ecommerce.order.client.dto.InventoryResponse;
import com.ecommerce.order.client.dto.StockChangeRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service", url = "${services.inventory.url}")
public interface InventoryClient {

    @PostMapping("/api/inventory/reserve")
    InventoryResponse reserve(@RequestBody StockChangeRequest request);

    @PostMapping("/api/inventory/release")
    InventoryResponse release(@RequestBody StockChangeRequest request);
}
