package com.example.loadgenerator.service;

import com.example.loadgenerator.model.LoadRequest;
import com.example.loadgenerator.model.LoadRun;
import com.example.loadgenerator.model.ScenarioRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoadRunnerService {

    private static final Logger log = LoggerFactory.getLogger(LoadRunnerService.class);

    private final RestTemplate restTemplate;
    private final String orderServiceUrl;
    private final MeterRegistry meterRegistry;
    private final Map<String, LoadRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> runFutures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicInteger activeRunCount = new AtomicInteger(0);

    public LoadRunnerService(
            RestTemplate restTemplate,
            @Value("${services.order.url}") String orderServiceUrl,
            MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.orderServiceUrl = orderServiceUrl;
        this.meterRegistry = meterRegistry;
        io.micrometer.core.instrument.Gauge.builder("loadgen.active.runs", activeRunCount, AtomicInteger::get)
                .register(meterRegistry);
    }

    public LoadRun startRun(LoadRequest request) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        LoadRun run = new LoadRun(runId, request.getScenario(), request.getRequestsPerSecond(), request.getDurationSeconds());
        runs.put(runId, run);
        activeRunCount.incrementAndGet();

        Future<?> future = executor.submit(() -> executeRun(run, request));
        runFutures.put(runId, future);

        log.info("Load run started: runId={}, scenario={}, rps={}, duration={}s",
                runId, request.getScenario(), request.getRequestsPerSecond(), request.getDurationSeconds());
        return run;
    }

    private void executeRun(LoadRun run, LoadRequest request) {
        String scenario = request.getScenario();
        int rps = request.getRequestsPerSecond();
        int concurrency = Math.max(1, request.getConcurrency());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        Counter requestCounter = Counter.builder("loadgen.requests.total")
                .tag("scenario", scenario).tag("status", "success").register(meterRegistry);
        Counter failCounter = Counter.builder("loadgen.requests.total")
                .tag("scenario", scenario).tag("status", "failure").register(meterRegistry);
        Timer latencyTimer = Timer.builder("loadgen.latency.seconds")
                .tag("scenario", scenario).register(meterRegistry);

        ScheduledFuture<?> statsLogger = scheduler.scheduleAtFixedRate(() -> logStats(run), 5, 5, TimeUnit.SECONDS);

        try {
            long intervalMs = 1000 / Math.max(1, rps);
            Instant deadline = Instant.now().plusSeconds(request.getDurationSeconds());

            while (Instant.now().isBefore(deadline) && !run.isCancelled()) {
                pool.submit(() -> {
                    long start = System.currentTimeMillis();
                    boolean success = false;
                    try {
                        executeScenarioRequest(scenario, request.getProductId());
                        success = true;
                        requestCounter.increment();
                    } catch (Exception e) {
                        failCounter.increment();
                    }
                    long latency = System.currentTimeMillis() - start;
                    run.recordRequest(latency, success);
                    latencyTimer.record(Duration.ofMillis(latency));
                });

                Thread.sleep(intervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            statsLogger.cancel(false);
            run.setStatus("COMPLETED");
            run.setEndedAt(Instant.now());
            activeRunCount.decrementAndGet();
            logStats(run);
            log.info("Load run completed: runId={}, totalRequests={}, successRate={}%",
                    run.getRunId(), run.getTotalRequests(), String.format("%.1f", run.getSuccessRate()));
        }
    }

    private void executeScenarioRequest(String scenario, String productId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String url = orderServiceUrl + "/api/orders";
        String pid = productId;
        String queryParams = "";

        switch (scenario) {
            case "OUT_OF_STOCK":
                pid = "prod-002";
                break;
            case "MIXED":
                String[] products = {"prod-001", "prod-002", "prod-003"};
                pid = products[ThreadLocalRandom.current().nextInt(products.length)];
                break;
            case "SLOW_INVENTORY":
                queryParams = "?simulateDelay=true";
                break;
            case "NOTIFICATION_FAIL":
                headers.set("X-Simulate-Error", "true");
                break;
            case "OVERLOAD":
                // Just send requests as fast as possible (handled by higher RPS in config)
                break;
            case "RAMP_UP":
                // Ramp handled externally via scenario endpoints
                break;
            case "HAPPY_PATH":
            default:
                break;
        }

        String body = String.format("{\"productId\":\"%s\",\"quantity\":1,\"customerEmail\":\"load@test.com\"}", pid);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        restTemplate.exchange(url + queryParams, HttpMethod.POST, entity, String.class);
    }

    private void logStats(LoadRun run) {
        int total = run.getTotalRequests();
        if (total == 0) return;
        double elapsed = Duration.between(run.getStartedAt(), Instant.now()).toMillis() / 1000.0;
        double currentRps = elapsed > 0 ? total / elapsed : 0;

        log.info("{\"event\":\"load_stats\",\"runId\":\"{}\",\"scenario\":\"{}\",\"totalRequests\":{},\"successRate\":{},\"avgLatencyMs\":{},\"p95LatencyMs\":{},\"rps\":{}}",
                run.getRunId(), run.getScenario(), total,
                String.format("%.1f", run.getSuccessRate()),
                run.getAvgLatencyMs(), run.getP95LatencyMs(),
                String.format("%.1f", currentRps));
    }

    public void stopRun(String runId) {
        LoadRun run = runs.get(runId);
        if (run != null && "RUNNING".equals(run.getStatus())) {
            run.setCancelled(true);
        }
    }

    public void stopAll() {
        runs.values().stream()
                .filter(r -> "RUNNING".equals(r.getStatus()))
                .forEach(r -> r.setCancelled(true));
    }

    public LoadRun getRun(String runId) {
        return runs.get(runId);
    }

    public Collection<LoadRun> getAllRuns() {
        return runs.values();
    }

    public List<LoadRun> getActiveRuns() {
        return runs.values().stream()
                .filter(r -> "RUNNING".equals(r.getStatus()))
                .toList();
    }

    // Scenario shortcuts
    public LoadRun startSpike(ScenarioRequest req) {
        LoadRequest lr = new LoadRequest();
        lr.setScenario("HAPPY_PATH");
        lr.setRequestsPerSecond(req.getPeakRps());
        lr.setDurationSeconds(req.getRampUpSeconds() + req.getSustainSeconds() + req.getRampDownSeconds());
        lr.setConcurrency(Math.max(3, req.getPeakRps() / 5));
        return startRun(lr);
    }

    public LoadRun startSoak(ScenarioRequest req) {
        LoadRequest lr = new LoadRequest();
        lr.setScenario("HAPPY_PATH");
        lr.setRequestsPerSecond(req.getRequestsPerSecond());
        lr.setDurationSeconds(req.getDurationMinutes() * 60);
        lr.setConcurrency(3);
        return startRun(lr);
    }

    public LoadRun startStress(ScenarioRequest req) {
        LoadRequest lr = new LoadRequest();
        lr.setScenario("RAMP_UP");
        lr.setRequestsPerSecond(req.getMaxRps());
        int steps = (req.getMaxRps() - req.getStartRps()) / Math.max(1, req.getStepRps());
        lr.setDurationSeconds(steps * req.getStepDurationSeconds());
        lr.setConcurrency(Math.max(3, req.getMaxRps() / 5));
        return startRun(lr);
    }

    public LoadRun startChaos(ScenarioRequest req) {
        LoadRequest lr = new LoadRequest();
        lr.setScenario("MIXED");
        lr.setRequestsPerSecond(5);
        lr.setDurationSeconds(req.getDurationSeconds());
        lr.setConcurrency(3);
        return startRun(lr);
    }
}
