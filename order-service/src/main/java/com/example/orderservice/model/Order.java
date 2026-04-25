package com.example.orderservice.model;

import java.time.Instant;

public class Order {
    private final String orderId;
    private final String productId;
    private final int quantity;
    private final String customerEmail;
    private String status;
    private final Instant createdAt;
    private String traceId;

    public Order(String orderId, String productId, int quantity, String customerEmail) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.customerEmail = customerEmail;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public String getOrderId() { return orderId; }
    public String getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public String getCustomerEmail() { return customerEmail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
