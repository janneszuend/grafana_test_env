package com.example.inventoryservice.controller;

import com.example.inventoryservice.model.Product;
import com.example.inventoryservice.service.InventoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);
    private final InventoryService inventoryService;
    private final Timer checkDuration;

    public InventoryController(InventoryService inventoryService, MeterRegistry meterRegistry) {
        this.inventoryService = inventoryService;
        this.checkDuration = Timer.builder("inventory.check.duration")
                .description("Duration of inventory checks")
                .register(meterRegistry);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<?> getInventory(
            @PathVariable String productId,
            @RequestParam(defaultValue = "false") boolean simulateDelay) {

        return checkDuration.record(() -> {
            if (simulateDelay) {
                try {
                    long delay = ThreadLocalRandom.current().nextLong(2000, 4001);
                    log.warn("Simulating slow inventory check for product={}, delay={}ms", productId, delay);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Product product = inventoryService.getProduct(productId);
            if (product == null) {
                log.warn("Product not found: {}", productId);
                return ResponseEntity.notFound().build();
            }

            log.info("Inventory check for product={}, stock={}", productId, product.getStock());
            return ResponseEntity.ok(Map.of(
                    "productId", product.getProductId(),
                    "name", product.getName(),
                    "stock", product.getStock(),
                    "available", product.getStock() > 0
            ));
        });
    }

    @PostMapping("/{productId}/reserve")
    public ResponseEntity<?> reserve(@PathVariable String productId, @RequestBody Map<String, Object> request) {
        int quantity = (int) request.getOrDefault("quantity", 1);
        String orderId = (String) request.getOrDefault("orderId", "unknown");

        Product product = inventoryService.getProduct(productId);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }

        boolean reserved = inventoryService.reserve(productId, quantity);
        if (!reserved) {
            log.warn("Reservation failed for product={}, orderId={}, requestedQty={}, availableStock={}",
                    productId, orderId, quantity, product.getStock());
            return ResponseEntity.status(409).body(Map.of(
                    "error", "OUT_OF_STOCK",
                    "productId", productId,
                    "availableStock", product.getStock()
            ));
        }

        log.info("Reserved {} units of product={} for orderId={}, remainingStock={}",
                quantity, productId, orderId, product.getStock());
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "reserved", quantity,
                "remainingStock", product.getStock(),
                "orderId", orderId
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        inventoryService.resetStock();
        log.info("Inventory stock reset to initial values");
        return ResponseEntity.ok(Map.of("status", "RESET", "message", "Stock reset to initial values"));
    }
}
