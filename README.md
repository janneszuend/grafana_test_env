# E-Commerce Order Processing — Grafana Cloud Observability PoC

## Voraussetzungen

- **Docker** + **Docker Compose** (v2)
- **Grafana Cloud Account** (Free-Tier reicht fuer diesen PoC)

Kein lokales Java oder Gradle noetig — alles wird per Multi-Stage Docker Build im Container gebaut.

## Architektur

```
┌──────────────┐  ┌──────────────────┐  ┌─────────────────────┐
│   Client /   │  │                  │  │                     │
│   Load Gen   │──▶  Order Service   │──▶  Inventory Service  │
│  (:8090)     │  │  (:8080)         │  │  (:8081)            │
└──────────────┘  │                  │──▶  Notification Svc   │
                  └────────┬─────────┘  │  (:8082)            │
                           │            └──────────┬──────────┘
                           │ OTel Javaagent        │ OTel Javaagent
                           │ (OTLP/gRPC)           │ (OTLP/gRPC)
                           ▼                       ▼
                  ┌────────────────────────────────────────────┐
                  │           Grafana Alloy (:4317)            │
                  │  ┌─────────────┐ ┌───────────────────────┐ │
                  │  │ OTLP Recv   │ │ Prometheus Scrape     │ │
                  │  │ Traces+Logs │ │ /actuator/prometheus  │ │
                  │  └──────┬──────┘ └───────────┬───────────┘ │
                  │         │                    │             │
                  │  ┌──────▼──────┐  ┌──────────▼──────────┐ │
                  │  │ Batch Proc  │  │ Docker Log Source    │ │
                  │  └──────┬──────┘  └──────────┬──────────┘ │
                  └─────────┼────────────────────┼────────────┘
                            │                    │
                            ▼                    ▼
                  ┌──────────────────────────────────────────┐
                  │          Grafana Cloud                    │
                  │  ┌───────┐  ┌───────┐  ┌──────┐          │
                  │  │ Tempo │  │ Mimir │  │ Loki │          │
                  │  │Traces │  │Metrics│  │ Logs │          │
                  │  └───────┘  └───────┘  └──────┘          │
                  │          Grafana Dashboards               │
                  └──────────────────────────────────────────┘
```

## Grafana Cloud Setup

Gehe zu [cloud.grafana.com](https://cloud.grafana.com) und erstelle einen Stack (oder nutze einen bestehenden).

| Variable                     | Wo zu finden                                                              |
|------------------------------|---------------------------------------------------------------------------|
| `GRAFANA_INSTANCE_ID`        | Stack Details → Grafana Instance ID                                       |
| `GRAFANA_CLOUD_API_KEY`      | Security → API Keys → Create (Role: **MetricsPublisher**)                 |
| `GRAFANA_OTLP_ENDPOINT`      | Stack Details → OpenTelemetry → Endpoint                                  |
| `GRAFANA_PROMETHEUS_ENDPOINT` | Stack Details → Prometheus → Remote Write Endpoint                        |
| `GRAFANA_LOKI_ENDPOINT`      | Stack Details → Loki → URL (mit `/loki/api/v1/push` am Ende)              |
| `GRAFANA_LOKI_USER`          | Stack Details → Loki → User                                               |

## Quick Start

```bash
# 1. Credentials konfigurieren
cp .env.example .env
# .env mit eigenen Grafana Cloud Credentials befuellen

# 2. Starten (baut alles automatisch im Container)
docker compose up --build
```

## Service-URLs

| Service             | URL                            |
|---------------------|--------------------------------|
| Frontend            | http://localhost:3000           |
| Order Service       | http://localhost:8080           |
| Inventory Service   | http://localhost:8081           |
| Notification Service| http://localhost:8082           |
| Load Generator      | http://localhost:8090           |
| Alloy UI            | http://localhost:12346          |
| Grafana Dashboards  | https://your-stack.grafana.net  |

## Curl-Beispiele

### a) Happy Path Order

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"prod-001","quantity":1,"customerEmail":"test@example.com"}'
```

### b) Out-of-Stock Order

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":"prod-002","quantity":1,"customerEmail":"test@example.com"}'
```

### c) Slow Inventory (simulateDelay)

```bash
curl -s -X POST "http://localhost:8080/api/orders?simulateDelay=true" \
  -H "Content-Type: application/json" \
  -d '{"productId":"prod-001","quantity":1,"customerEmail":"test@example.com"}'
```

### d) Notification Failure

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "X-Simulate-Error: true" \
  -d '{"productId":"prod-001","quantity":1,"customerEmail":"test@example.com"}'
```

### e) Load Generator starten (HAPPY_PATH, 10 RPS, 60s)

```bash
curl -s -X POST http://localhost:8090/api/load/start \
  -H "Content-Type: application/json" \
  -d '{"scenario":"HAPPY_PATH","requestsPerSecond":10,"durationSeconds":60,"concurrency":3,"productId":"prod-001"}'
```

### f) Spike-Test starten

```bash
curl -s -X POST http://localhost:8090/api/load/scenarios/spike \
  -H "Content-Type: application/json" \
  -d '{"peakRps":50,"rampUpSeconds":10,"sustainSeconds":30,"rampDownSeconds":10}'
```

### g) Chaos-Test starten

```bash
curl -s -X POST http://localhost:8090/api/load/scenarios/chaos \
  -H "Content-Type: application/json" \
  -d '{"durationSeconds":120,"errorMixPercent":30}'
```

### h) Status abfragen

```bash
curl -s http://localhost:8090/api/load/status | jq
```

### i) Stop

```bash
curl -s -X POST http://localhost:8090/api/load/stop
```

### j) Inventory Reset

```bash
curl -s -X POST http://localhost:8080/api/inventory/reset
```

## Was in Grafana Cloud zu sehen ist

### Tempo (Distributed Tracing)

- **Waterfall-View** eines kompletten Order-Flows mit 3-4 Spans:
  `order-service` → `inventory-service` (stock check) → `inventory-service` (reserve) → `notification-service`
- **Error-Spans** bei Out-of-Stock (HTTP 409) werden rot markiert
- **Lange Spans** bei `simulateDelay=true` — der Inventory-Check-Span dauert 2-4 Sekunden
- Notification-Fehler erzeugen einen Error-Span, aber die Order bleibt PLACED

### Mimir (Metrics / Prometheus)

- `orders_placed_total` — zaehlt erfolgreiche Bestellungen
- `orders_failed_total{reason="out_of_stock"}` — zaehlt fehlgeschlagene Bestellungen nach Grund
- `inventory_stock_level{product_id="prod-001"}` — aktueller Lagerstand je Produkt
- `loadgen_latency_seconds` — Latenz-Histogramm (p95/p99 sichtbar waehrend Spike-Tests)
- `loadgen_requests_total{scenario,status}` — Request-Counter je Szenario
- Spring Boot Actuator Metrics (JVM, HTTP, etc.)

### Loki (Logs)

- **JSON-strukturierte Logs** mit `trace_id` und `span_id` in jedem Log-Eintrag
- **Trace-zu-Log-Korrelation**: Aus einer Log-Zeile direkt in Tempo zum zugehoerigen Trace springen
- Filter nach Service: `{container_name=~".*order-service.*"}`
- Business-Events auf INFO-Level, degraded mode auf WARN (z.B. Notification fehlgeschlagen), Fehler auf ERROR
