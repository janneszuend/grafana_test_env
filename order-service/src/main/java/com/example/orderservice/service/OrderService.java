package com.example.orderservice.service;

import com.example.orderservice.model.Order;
import com.example.orderservice.model.OrderRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RestTemplate inventoryRestTemplate;
    private final RestTemplate notificationRestTemplate;
    private final String inventoryUrl;
    private final String notificationUrl;
    private final Counter ordersPlaced;
    private final Counter ordersFailedOutOfStock;
    private final Counter ordersFailedTimeout;
    private final Counter ordersFailedNotification;
    private final Timer inventoryCheckDuration;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public OrderService(
            @Qualifier("inventoryRestTemplate") RestTemplate inventoryRestTemplate,
            @Qualifier("notificationRestTemplate") RestTemplate notificationRestTemplate,
            @Value("${services.inventory.url}") String inventoryUrl,
            @Value("${services.notification.url}") String notificationUrl,
            MeterRegistry meterRegistry) {
        this.inventoryRestTemplate = inventoryRestTemplate;
        this.notificationRestTemplate = notificationRestTemplate;
        this.inventoryUrl = inventoryUrl;
        this.notificationUrl = notificationUrl;

        this.ordersPlaced = Counter.builder("orders.placed.total")
                .description("Total successful orders").register(meterRegistry);
        this.ordersFailedOutOfStock = Counter.builder("orders.failed.total")
                .tag("reason", "out_of_stock").register(meterRegistry);
        this.ordersFailedTimeout = Counter.builder("orders.failed.total")
                .tag("reason", "timeout").register(meterRegistry);
        this.ordersFailedNotification = Counter.builder("orders.failed.total")
                .tag("reason", "notification_error").register(meterRegistry);
        this.inventoryCheckDuration = Timer.builder("inventory.check.duration")
                .description("Duration of inventory check from order service").register(meterRegistry);
    }

    public Order placeOrder(OrderRequest request, boolean simulateDelay, boolean simulateNotificationError) {
        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, request.getProductId(), request.getQuantity(),
                request.getCustomerEmail() != null ? request.getCustomerEmail() : "customer@example.com");
        order.setTraceId(MDC.get("trace_id"));

        log.info("Processing order: orderId={}, productId={}, quantity={}",
                orderId, request.getProductId(), request.getQuantity());

        // Step 1: Check inventory
        Map<String, Object> stockCheck;
        try {
            stockCheck = inventoryCheckDuration.record(() -> {
                String url = inventoryUrl + "/api/inventory/" + request.getProductId();
                if (simulateDelay) {
                    url += "?simulateDelay=true";
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> result = inventoryRestTemplate.getForObject(url, Map.class);
                return result;
            });
        } catch (Exception e) {
            log.error("Inventory check failed for orderId={}: {}", orderId, e.getMessage());
            ordersFailedTimeout.increment();
            order.setStatus("FAILED");
            orders.put(orderId, order);
            throw new OrderException("Inventory service unavailable", 503);
        }

        boolean available = stockCheck != null && Boolean.TRUE.equals(stockCheck.get("available"));
        if (!available) {
            log.warn("Out of stock for orderId={}, productId={}", orderId, request.getProductId());
            ordersFailedOutOfStock.increment();
            order.setStatus("REJECTED");
            orders.put(orderId, order);
            throw new OutOfStockException(request.getProductId());
        }

        // Step 2: Reserve inventory
        try {
            inventoryRestTemplate.postForObject(
                    inventoryUrl + "/api/inventory/" + request.getProductId() + "/reserve",
                    Map.of("quantity", request.getQuantity(), "orderId", orderId),
                    Map.class);
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Reservation conflict for orderId={}, productId={}", orderId, request.getProductId());
            ordersFailedOutOfStock.increment();
            order.setStatus("REJECTED");
            orders.put(orderId, order);
            throw new OutOfStockException(request.getProductId());
        } catch (Exception e) {
            log.error("Reservation failed for orderId={}: {}", orderId, e.getMessage());
            ordersFailedTimeout.increment();
            order.setStatus("FAILED");
            orders.put(orderId, order);
            throw new OrderException("Inventory reservation failed", 503);
        }

        // Step 3: Send notification
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (simulateNotificationError) {
                headers.set("X-Simulate-Error", "true");
            }
            HttpEntity<Map<String, String>> notificationRequest = new HttpEntity<>(
                    Map.of("type", "ORDER_CONFIRMATION",
                            "recipient", order.getCustomerEmail(),
                            "orderId", orderId,
                            "payload", "Order " + orderId + " confirmed for " + request.getProductId()),
                    headers);
            notificationRestTemplate.postForObject(
                    notificationUrl + "/api/notifications",
                    notificationRequest, Map.class);
        } catch (Exception e) {
            log.warn("Notification failed for orderId={}, but order is placed: {}", orderId, e.getMessage());
            ordersFailedNotification.increment();
        }

        order.setStatus("PLACED");
        orders.put(orderId, order);
        ordersPlaced.increment();
        log.info("Order placed successfully: orderId={}, productId={}", orderId, request.getProductId());
        return order;
    }

    public Order getOrder(String orderId) {
        return orders.get(orderId);
    }

    public void resetInventory() {
        inventoryRestTemplate.postForObject(inventoryUrl + "/api/inventory/reset", null, Map.class);
    }

    public static class OutOfStockException extends RuntimeException {
        private final String productId;
        public OutOfStockException(String productId) {
            super("Product " + productId + " is out of stock");
            this.productId = productId;
        }
        public String getProductId() { return productId; }
    }

    public static class OrderException extends RuntimeException {
        private final int status;
        public OrderException(String message, int status) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
