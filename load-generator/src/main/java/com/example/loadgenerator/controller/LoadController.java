package com.example.loadgenerator.controller;

import com.example.loadgenerator.model.LoadRequest;
import com.example.loadgenerator.model.LoadRun;
import com.example.loadgenerator.model.PeriodicRequest;
import com.example.loadgenerator.model.ScenarioRequest;
import com.example.loadgenerator.service.LoadRunnerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/load")
public class LoadController {

    private final LoadRunnerService loadRunnerService;

    public LoadController(LoadRunnerService loadRunnerService) {
        this.loadRunnerService = loadRunnerService;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody LoadRequest request) {
        LoadRun run = loadRunnerService.startRun(request);
        return ResponseEntity.accepted().body(Map.of(
                "runId", run.getRunId(),
                "scenario", run.getScenario(),
                "status", run.getStatus(),
                "startedAt", run.getStartedAt().toString(),
                "estimatedEndAt", run.getEstimatedEndAt().toString()
        ));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stopAll() {
        loadRunnerService.stopAll();
        return ResponseEntity.ok(Map.of("status", "ALL_STOPPED"));
    }

    @PostMapping("/stop/{runId}")
    public ResponseEntity<?> stop(@PathVariable String runId) {
        loadRunnerService.stopRun(runId);
        return ResponseEntity.ok(Map.of("runId", runId, "status", "STOPPING"));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        List<Map<String, Object>> activeRuns = loadRunnerService.getActiveRuns().stream()
                .map(this::toRunMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("activeRuns", activeRuns));
    }

    @GetMapping("/reports")
    public ResponseEntity<?> reports() {
        List<Map<String, Object>> allRuns = loadRunnerService.getAllRuns().stream()
                .map(this::toRunMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(allRuns);
    }

    @GetMapping("/reports/{runId}")
    public ResponseEntity<?> report(@PathVariable String runId) {
        LoadRun run = loadRunnerService.getRun(runId);
        if (run == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toRunMap(run));
    }

    @PostMapping("/scenarios/spike")
    public ResponseEntity<?> spike(@RequestBody ScenarioRequest request) {
        LoadRun run = loadRunnerService.startSpike(request);
        return ResponseEntity.accepted().body(toRunMap(run));
    }

    @PostMapping("/scenarios/soak")
    public ResponseEntity<?> soak(@RequestBody ScenarioRequest request) {
        LoadRun run = loadRunnerService.startSoak(request);
        return ResponseEntity.accepted().body(toRunMap(run));
    }

    @PostMapping("/scenarios/stress")
    public ResponseEntity<?> stress(@RequestBody ScenarioRequest request) {
        LoadRun run = loadRunnerService.startStress(request);
        return ResponseEntity.accepted().body(toRunMap(run));
    }

    @PostMapping("/scenarios/chaos")
    public ResponseEntity<?> chaos(@RequestBody ScenarioRequest request) {
        LoadRun run = loadRunnerService.startChaos(request);
        return ResponseEntity.accepted().body(toRunMap(run));
    }

    @PostMapping("/periodic/start")
    public ResponseEntity<?> startPeriodic(@RequestBody(required = false) PeriodicRequest request) {
        if (request == null) request = new PeriodicRequest();
        return ResponseEntity.accepted().body(loadRunnerService.startPeriodic(request));
    }

    @PostMapping("/periodic/stop")
    public ResponseEntity<?> stopPeriodic() {
        return ResponseEntity.ok(loadRunnerService.stopPeriodic());
    }

    @GetMapping("/periodic/status")
    public ResponseEntity<?> periodicStatus() {
        return ResponseEntity.ok(loadRunnerService.periodicStatus());
    }

    private Map<String, Object> toRunMap(LoadRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", run.getRunId());
        map.put("scenario", run.getScenario());
        map.put("status", run.getStatus());
        map.put("startedAt", run.getStartedAt().toString());
        if (run.getEndedAt() != null) map.put("endedAt", run.getEndedAt().toString());
        map.put("stats", Map.of(
                "totalRequests", run.getTotalRequests(),
                "successRate", String.format("%.1f%%", run.getSuccessRate()),
                "avgLatencyMs", run.getAvgLatencyMs(),
                "p95LatencyMs", run.getP95LatencyMs(),
                "requestsPerSecond", run.getTargetRps()
        ));
        return map;
    }
}
