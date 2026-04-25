package com.example.inventoryservice.model;

public class Product {
    private final String productId;
    private final String name;
    private int stock;

    public Product(String productId, String name, int stock) {
        this.productId = productId;
        this.name = name;
        this.stock = stock;
    }

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public synchronized boolean reserve(int quantity) {
        if (stock >= quantity) {
            stock -= quantity;
            return true;
        }
        return false;
    }
}
