package com.example.balanceservice.model;

public class Account {
    private final String accountId;
    private final String ownerName;
    private double balance;

    public Account(String accountId, String ownerName, double balance) {
        this.accountId = accountId;
        this.ownerName = ownerName;
        this.balance = balance;
    }

    public String getAccountId() { return accountId; }
    public String getOwnerName() { return ownerName; }
    public double getBalance() { return balance; }

    public synchronized void credit(double amount) {
        this.balance += amount;
    }

    public synchronized boolean debit(double amount) {
        if (balance >= amount) {
            balance -= amount;
            return true;
        }
        return false;
    }
}
