# Architektur-Beschreibung — Grafana Cloud Observability PoC

Simuliertes Banking-System mit vier Microservices plus Load Generator und Frontend.
Jeder Service sendet Traces, Metrics und Logs via OpenTelemetry an Grafana Alloy,
der diese an Grafana Cloud weiterleitet (Tempo / Mimir / Loki).

---

## Übersicht

```
Frontend (:3000)         Load Generator (:8090)
       │                         │
       └──────────┬──────────────┘
                  ▼
         account-service (:8080)
           │            │
           ▼            ▼
    balance-service  notification-service
       (:8081)           (:8082)
           │
           │  (alle Services)
           ▼
      Grafana Alloy (:4317 gRPC / :4318 HTTP / UI :12345)
           │
           ▼
      Grafana Cloud (Tempo · Mimir · Loki)
```

---

## Services

### account-service — `:8080`

Einstiegspunkt für alle Zahlungsvorgänge. Nimmt Transaktionen entgegen,
prüft das Guthaben via balance-service und löst Benachrichtigungen aus.

**Testkonten (in-memory, Reset bei Neustart):**
| Account-ID | Inhaber | Start-Guthaben |
|------------|---------|----------------|
| `acc-001`  | Alice   | 1 000 CHF      |
| `acc-002`  | Bob     | 500 CHF        |

**Endpoints:**

```
POST /api/transactions
```
Erstellt eine Transaktion.

Request-Body:
```json
{
  "type": "TRANSFER",        // oder "DEPOSIT"
  "fromAccountId": "acc-001",
  "toAccountId": "acc-002",
  "amount": 100.0
}
```
Query-Parameter: `?simulateDelay=true` — verlangsamt den Balance-Check (2–4 s).
Header: `X-Simulate-Error: true` — lässt die Benachrichtigung fehlschlagen (Transaction bleibt COMPLETED).

Antwort `201`:
```json
{
  "transactionId": "uuid",
  "type": "TRANSFER",
  "status": "COMPLETED",
  "amount": 100.0,
  "currency": "CHF",
  "traceId": "..."
}
```
Fehler `409` bei unzureichendem Guthaben, `503` wenn balance-service nicht erreichbar.

---

```
GET /api/transactions/{id}
```
Gibt eine gespeicherte Transaktion zurück (nur im Prozess-Speicher, kein DB).

---

```
POST /api/accounts/reset
```
Setzt alle Kontostände auf die Startwerte zurück (delegiert an balance-service).

---

**Typische curl-Beispiele:**

```bash
# Happy Path Transfer
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"type":"TRANSFER","fromAccountId":"acc-001","toAccountId":"acc-002","amount":50}'

# Deposit
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"type":"DEPOSIT","toAccountId":"acc-001","amount":200}'

# Insufficient Funds (409)
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"type":"TRANSFER","fromAccountId":"acc-002","toAccountId":"acc-001","amount":9999}'

# Langsamer Balance-Check (Trace zeigt langen Span)
curl -s -X POST "http://localhost:8080/api/transactions?simulateDelay=true" \
  -H "Content-Type: application/json" \
  -d '{"type":"TRANSFER","fromAccountId":"acc-001","toAccountId":"acc-002","amount":10}'

# Notification-Fehler (Transaction trotzdem COMPLETED, aber Error-Span)
curl -s -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -H "X-Simulate-Error: true" \
  -d '{"type":"TRANSFER","fromAccountId":"acc-001","toAccountId":"acc-002","amount":10}'

# Reset
curl -s -X POST http://localhost:8080/api/accounts/reset
```

---

### balance-service — `:8081`

Verwaltet Kontostände in-memory. Wird ausschliesslich vom account-service aufgerufen.

**Endpoints:**

```
GET  /api/balance/{accountId}[?simulateDelay=true]
POST /api/balance/{accountId}/credit     { "amount": 100.0, "transactionId": "..." }
POST /api/balance/{accountId}/debit      { "amount": 100.0, "transactionId": "..." }
POST /api/balance/reset
```

Debit gibt `409` zurück wenn das Guthaben nicht reicht.

```bash
# Kontostand abfragen
curl -s http://localhost:8081/api/balance/acc-001

# Manueller Credit
curl -s -X POST http://localhost:8081/api/balance/acc-001/credit \
  -H "Content-Type: application/json" \
  -d '{"amount":500,"transactionId":"manual-001"}'

# Reset
curl -s -X POST http://localhost:8081/api/balance/reset
```

**Custom Metrics:**
- `account.balance.chf{account_id, owner}` — aktueller Kontostand als Gauge
- `balance.check.duration` — Histogramm der Balance-Check-Dauer

---

### notification-service — `:8082`

Simuliert den Versand von Transaktionsbestätigungen. Kein realer Versand — loggt nur.

**Endpoint:**

```
POST /api/notifications
```
Header: `X-Simulate-Error: true` → gibt `500` zurück (für Fehler-Traces).

```json
{
  "type": "TRANSFER_CONFIRMATION",
  "recipient": "acc-002@bank.example",
  "transactionId": "uuid",
  "payload": "TRANSFER of 50.0 CHF completed"
}
```

```bash
# Normaler Versand
curl -s -X POST http://localhost:8082/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"TRANSFER_CONFIRMATION","recipient":"bob@example.com","transactionId":"tx-1"}'

# Fehler simulieren
curl -s -X POST http://localhost:8082/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-Simulate-Error: true" \
  -d '{"type":"TRANSFER_CONFIRMATION","recipient":"bob@example.com","transactionId":"tx-1"}'
```

---

### load-generator — `:8090`

Generiert synthetische Last gegen den account-service. Eigene OTel-Instrumentierung
(Latenz-Histogramm, Request-Counter).

**Endpoints:**

```
POST /api/load/start          Freier Load-Run
POST /api/load/stop           Alle Runs stoppen
POST /api/load/stop/{runId}   Einzelnen Run stoppen
GET  /api/load/status         Aktive Runs
GET  /api/load/reports        Alle abgeschlossenen Runs
GET  /api/load/reports/{id}   Einzelner Run-Report

POST /api/load/scenarios/spike    Lastspitze
POST /api/load/scenarios/soak     Dauerlast
POST /api/load/scenarios/stress   Stufenweise Steigerung
POST /api/load/scenarios/chaos    Mix aus Fehler-Szenarien
```

```bash
# Normaler Load (5 RPS, 60 s)
curl -s -X POST http://localhost:8090/api/load/start \
  -H "Content-Type: application/json" \
  -d '{"scenario":"TRANSFER","requestsPerSecond":5,"durationSeconds":60,"concurrency":3,"accountId":"acc-001"}'

# Spike-Test
curl -s -X POST http://localhost:8090/api/load/scenarios/spike \
  -H "Content-Type: application/json" \
  -d '{"peakRps":50,"rampUpSeconds":10,"sustainSeconds":30,"rampDownSeconds":10}'

# Soak-Test (Dauerlast)
curl -s -X POST http://localhost:8090/api/load/scenarios/soak \
  -H "Content-Type: application/json" \
  -d '{"requestsPerSecond":5,"durationMinutes":10}'

# Stress-Test (stufenweise)
curl -s -X POST http://localhost:8090/api/load/scenarios/stress \
  -H "Content-Type: application/json" \
  -d '{"startRps":5,"stepRps":10,"stepDurationSeconds":30,"maxRps":50}'

# Chaos-Test (30 % Fehler)
curl -s -X POST http://localhost:8090/api/load/scenarios/chaos \
  -H "Content-Type: application/json" \
  -d '{"durationSeconds":120,"errorMixPercent":30}'

# Status
curl -s http://localhost:8090/api/load/status | jq

# Alles stoppen
curl -s -X POST http://localhost:8090/api/load/stop
```

---

### Grafana Alloy — `:4317` / `:4318` / UI `:12345`

OTel-Collector. Empfängt alle Signale der Services (OTLP gRPC auf Port 4317,
OTLP HTTP auf Port 4318) und leitet sie an Grafana Cloud weiter.

Die Konfiguration liegt in `alloy/config.alloy`. Relevante Einstellungen:
- Remote-Config via Fleet Management (zieht Konfig von `fleet-management-prod-024.grafana.net`)
- Fügt `application=TestApplikationJannes` und `namespace=macbookpro-jannes` als Resource-Attribute hinzu
- Exportiert an `otlp-gateway-prod-eu-central-0.grafana.net`

Alloy UI zum Debuggen: [http://localhost:12345](http://localhost:12345)

---

## Observability in Grafana Cloud

| Signal  | Backend | Was zu sehen ist |
|---------|---------|-----------------|
| Traces  | Tempo   | Vollständiger Span-Tree pro Transaktion (account → balance → notification) |
| Metrics | Mimir   | `transactions.completed.total`, `transactions.failed.total{reason}`, `account.balance.chf{account_id,owner}`, `balance.check.duration`, JVM-/HTTP-Metriken |
| Logs    | Loki    | JSON-Logs mit `trace_id` / `span_id` — direkt aus Loki in Tempo navigierbar |

---

## Bekannte Inkonsistenzen (Work in Progress)

| Was | Ist | Sollte sein |
|-----|-----|-------------|
| Docker-Service-Name | `order-service` | `account-service` |
| Docker-Service-Name | `inventory-service` | `balance-service` |
| Alloy-Exporter-Endpoint | `prod-eu-central-0` | muss zum `.env`-Stack passen |
