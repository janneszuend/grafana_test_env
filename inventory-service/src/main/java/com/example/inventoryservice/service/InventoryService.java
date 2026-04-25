package com.example.inventoryservice.service;

import com.example.inventoryservice.model.Product;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryService {

    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public InventoryService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        resetStock();
    }

    public Product getProduct(String productId) {
        return products.get(productId);
    }

    public boolean reserve(String productId, int quantity) {
        Product product = products.get(productId);
        if (product == null) {
            return false;
        }
        return product.reserve(quantity);
    }

    public void resetStock() {
        products.clear();
        products.put("prod-001", new Product("prod-001", "MacBook Pro 14", 10));
        products.put("prod-002", new Product("prod-002", "iPhone 16 Pro", 0));
        products.put("prod-003", new Product("prod-003", "AirPods Pro", 50));

        products.forEach((id, product) ->
            Gauge.builder("inventory.stock.level", product, p -> (double) p.getStock())
                .tag("product_id", id)
                .register(meterRegistry)
        );
    }
}
