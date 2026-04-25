package com.example.loadgenerator.model;

public class LoadRequest {
    private String scenario = "HAPPY_PATH";
    private int requestsPerSecond = 5;
    private int durationSeconds = 60;
    private int concurrency = 3;
    private String productId = "prod-001";

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(int requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
}
