package com.example.orderservice.controller;

import com.example.orderservice.model.Order;
import com.example.orderservice.model.OrderRequest;
import com.example.orderservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(
            @RequestBody OrderRequest request,
            @RequestParam(defaultValue = "false") boolean simulateDelay,
            @RequestHeader(value = "X-Simulate-Error", defaultValue = "false") boolean simulateNotificationError) {
        Order order = orderService.placeOrder(request, simulateDelay, simulateNotificationError);
        return ResponseEntity.status(201).body(Map.of(
                "orderId", order.getOrderId(),
                "status", order.getStatus(),
                "traceId", order.getTraceId() != null ? order.getTraceId() : ""
        ));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<?> getOrder(@PathVariable String id) {
        Order order = orderService.getOrder(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "orderId", order.getOrderId(),
                "productId", order.getProductId(),
                "quantity", order.getQuantity(),
                "status", order.getStatus(),
                "createdAt", order.getCreatedAt().toString()
        ));
    }

    @PostMapping("/inventory/reset")
    public ResponseEntity<?> resetInventory() {
        orderService.resetInventory();
        log.info("Inventory reset triggered via order service");
        return ResponseEntity.ok(Map.of("status", "RESET"));
    }

    @ExceptionHandler(OrderService.OutOfStockException.class)
    public ResponseEntity<?> handleOutOfStock(OrderService.OutOfStockException ex) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "OUT_OF_STOCK",
                "productId", ex.getProductId(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(OrderService.OrderException.class)
    public ResponseEntity<?> handleOrderException(OrderService.OrderException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", "ORDER_FAILED",
                "message", ex.getMessage()
        ));
    }
}
