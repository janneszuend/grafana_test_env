package com.example.accountservice.model;

public class TransactionRequest {
    private String type = "TRANSFER";
    private String fromAccountId;
    private String toAccountId;
    private double amount;
    private String recipientEmail;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFromAccountId() { return fromAccountId; }
    public void setFromAccountId(String fromAccountId) { this.fromAccountId = fromAccountId; }
    public String getToAccountId() { return toAccountId; }
    public void setToAccountId(String toAccountId) { this.toAccountId = toAccountId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
}
