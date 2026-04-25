package com.example.loadgenerator.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LoadRun {
    private final String runId;
    private final String scenario;
    private volatile String status;
    private final Instant startedAt;
    private volatile Instant endedAt;
    private final int targetRps;
    private final int durationSeconds;
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private volatile long p95LatencyMs;
    private volatile boolean cancelled;

    public LoadRun(String runId, String scenario, int targetRps, int durationSeconds) {
        this.runId = runId;
        this.scenario = scenario;
        this.targetRps = targetRps;
        this.durationSeconds = durationSeconds;
        this.status = "RUNNING";
        this.startedAt = Instant.now();
    }

    public void recordRequest(long latencyMs, boolean success) {
        totalRequests.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        if (success) {
            successCount.incrementAndGet();
        } else {
            failureCount.incrementAndGet();
        }
    }

    public String getRunId() { return runId; }
    public String getScenario() { return scenario; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public int getTargetRps() { return targetRps; }
    public int getDurationSeconds() { return durationSeconds; }
    public int getTotalRequests() { return totalRequests.get(); }
    public int getSuccessCount() { return successCount.get(); }
    public int getFailureCount() { return failureCount.get(); }
    public long getTotalLatencyMs() { return totalLatencyMs.get(); }
    public long getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(long p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    public double getSuccessRate() {
        int total = totalRequests.get();
        return total == 0 ? 0.0 : (double) successCount.get() / total * 100.0;
    }

    public long getAvgLatencyMs() {
        int total = totalRequests.get();
        return total == 0 ? 0 : totalLatencyMs.get() / total;
    }

    public Instant getEstimatedEndAt() {
        return startedAt.plusSeconds(durationSeconds);
    }
}
