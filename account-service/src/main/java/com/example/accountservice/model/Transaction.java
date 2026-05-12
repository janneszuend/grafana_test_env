package com.example.accountservice.model;

import java.time.Instant;

public class Transaction {
    private final String transactionId;
    private final String type;
    private final String fromAccountId;
    private final String toAccountId;
    private final double amount;
    private final String currency;
    private String status;
    private final Instant createdAt;

    public Transaction(String transactionId, String type, String fromAccountId, String toAccountId, double amount) {
        this.transactionId = transactionId;
        this.type = type;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = "CHF";
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public String getTransactionId() { return transactionId; }
    public String getType() { return type; }
    public String getFromAccountId() { return fromAccountId; }
    public String getToAccountId() { return toAccountId; }
    public double getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
