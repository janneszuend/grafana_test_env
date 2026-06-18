# Kubernetes-Deployment (k3d + Kustomize)

PoC-Setup für lokales K8s. Spiegelt das `docker-compose.yml` 1:1: gleiche Service-Namen, gleiche Ports, gleiche OTel-Env-Vars.

## Voraussetzungen

```bash
brew install k3d kubectl
```

Docker muss laufen (k3d lässt k3s in Docker-Containern laufen).

## Erstmaliges Setup

### 1. Cluster anlegen

```bash
k3d cluster create banking-poc
```

`kubectl config current-context` sollte jetzt `k3d-banking-poc` zeigen.

### 2. Images bauen + in den Cluster importieren

```bash
# vom Repo-Root aus
docker build -t banking-poc/account-service:dev      ./account-service
docker build -t banking-poc/balance-service:dev      ./balance-service
docker build -t banking-poc/notification-service:dev ./notification-service
docker build -t banking-poc/load-generator:dev       ./load-generator
docker build -t banking-poc/frontend:dev             ./frontend

k3d image import \
  banking-poc/account-service:dev \
  banking-poc/balance-service:dev \
  banking-poc/notification-service:dev \
  banking-poc/load-generator:dev \
  banking-poc/frontend:dev \
  -c banking-poc
```

### 3. Grafana-Cloud-Credentials als Secret anlegen

Das `.env` ist gitignored — daraus wird ein K8s-Secret. Namespace muss existieren, also vorher Namespace applien:

```bash
kubectl apply -f k8s/base/namespace.yaml

# .env in die Shell laden (parst Quotes korrekt) und nur die benötigten Keys ins Secret schreiben.
# Wichtig: NICHT `--from-env-file=.env` — das übernimmt Anführungszeichen literal → 401 bei Alloy.
set -a; source .env; set +a
kubectl -n banking-poc create secret generic grafana-cloud \
  --from-literal=GRAFANA_APP_ID="$GRAFANA_APP_ID" \
  --from-literal=GRAFANA_OTEL_API_KEY="$GRAFANA_OTEL_API_KEY" \
  --from-literal=GRAFANA_PROMETHEUS_APP_ID="$GRAFANA_PROMETHEUS_APP_ID" \
  --from-literal=GRAFANA_PROMETHEUS_API_KEY="$GRAFANA_PROMETHEUS_API_KEY" \
  --from-literal=GRAFANA_PROMETHEUS_ENDPOINT="$GRAFANA_PROMETHEUS_ENDPOINT"
```

Das Secret stellt alle `GRAFANA_*`-Vars als Env-Vars im Alloy-Pod bereit (per `envFrom`).

### 4. Manifeste applien

```bash
kubectl apply -k k8s/base
```

Status prüfen:

```bash
kubectl -n banking-poc get pods
kubectl -n banking-poc logs deploy/alloy
```

### 5. Frontend + APIs lokal erreichbar machen

```bash
# Frontend
kubectl -n banking-poc port-forward svc/frontend 3000:3000 &

# (optional) direkte Service-Zugriffe wie unter docker-compose
kubectl -n banking-poc port-forward svc/account-service 8080:8080 &
kubectl -n banking-poc port-forward svc/balance-service 8081:8081 &
kubectl -n banking-poc port-forward svc/notification-service 8082:8082 &
kubectl -n banking-poc port-forward svc/load-generator 8090:8090 &

# Alloy UI zum Debuggen
kubectl -n banking-poc port-forward svc/alloy 12345:12345 &
```

Dann wie gewohnt: <http://localhost:3000>, `curl http://localhost:8080/...`, etc.

## Grafana k8s-monitoring (Cluster-Telemetrie, parallel zum App-Alloy)

Der App-Alloy in `banking-poc` schickt Traces/Metrics/Logs **deiner Services**. Der k8s-monitoring Helm Chart deployt einen **zweiten** Alloy-Stack im Namespace `dev`, der **Cluster-Telemetrie** sammelt (node-exporter, kube-state-metrics, opencost, kepler, Pod-Logs via Loki, Cluster-Events).

Beide laufen unabhängig und schreiben in denselben Grafana-Cloud-Stack.

### Voraussetzungen

```bash
brew install helm
```

### Values-Datei anlegen

`k8s/k8s-monitoring/values.yaml` ist **gitignored** (enthält Cloud-Access-Policy-Tokens). Aus dem Example kopieren und befüllen:

```bash
cp k8s/k8s-monitoring/values.example.yaml k8s/k8s-monitoring/values.yaml
# tokens & URLs aus Grafana Cloud → "Kubernetes Monitoring" → "Cluster configuration" eintragen
```

### Installieren

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
helm upgrade --install --atomic --timeout 300s grafana-k8s-monitoring grafana/k8s-monitoring \
  --version "^4" \
  --namespace dev \
  --create-namespace \
  --values k8s/k8s-monitoring/values.yaml
```

### Status

```bash
kubectl -n dev get pods
# erwartet: alloy-metrics-*, alloy-logs-*, alloy-singleton-*, kube-state-metrics-*, node-exporter-*, opencost-*, kepler-*
```

### Caveats für k3d

- **kepler** (Energy-Metrics) braucht Kernel-/eBPF-Zugriff. In k3d (containerisierte Nodes) crasht der Pod oft mit `RuntimeError`. Wenn das stört: `kepler.deploy: false` setzen.
- **node-exporter** sieht die Node-Container, **nicht** dein Mac-Host. Disk-/Netzwerk-/Temp-Metriken sind irreführend. Für ein PoC ok.
- **windows-exporter** läuft auf k3d nicht (Linux-Nodes), die Pods schedulen aber nicht (NodeSelector), also nicht weiter störend.

### Deinstallieren

```bash
helm uninstall grafana-k8s-monitoring -n dev
kubectl delete namespace dev
```

## Workflow nach Code-Änderung

```bash
# 1. Image neu bauen
docker build -t banking-poc/account-service:dev ./account-service

# 2. In Cluster importieren
k3d image import banking-poc/account-service:dev -c banking-poc

# 3. Pod neu starten (zieht das neue Image, weil imagePullPolicy=IfNotPresent)
kubectl -n banking-poc rollout restart deploy/account-service
```

## Aufräumen

```bash
# Nur Workloads löschen
kubectl delete -k k8s/base

# Cluster komplett weg
k3d cluster delete banking-poc
```

## Aufbau

```
k8s/
├── base/                         # Eigene App-Manifeste (Kustomize)
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── alloy.yaml                # App-Alloy (eigene config.alloy)
│   ├── alloy/
│   │   └── config.alloy
│   ├── account-service.yaml
│   ├── balance-service.yaml
│   ├── notification-service.yaml
│   ├── load-generator.yaml
│   └── frontend.yaml
└── k8s-monitoring/               # Grafana k8s-monitoring Helm Chart
    ├── values.example.yaml       # committed (Platzhalter)
    └── values.yaml               # gitignored (echte Tokens)
```

## Unterschiede zu docker-compose

| Thema | docker-compose | K8s |
|-------|----------------|-----|
| Alloy Docker-Scrape (`host.docker.internal:9323`) | aktiv | entfernt — kein Docker-Daemon im Pod |
| Docker-Socket-Mount | gemountet | nicht gemountet |
| nginx-Resolver `127.0.0.11` | aktiv | entfernt — Docker-spezifisch |
| Service-DNS (`account-service:8080`) | Docker-DNS | K8s-Service-DNS (verhält sich gleich) |
| `.env` | direkt eingelesen (`env_file`) | als Secret `grafana-cloud` (per `envFrom`) |
| Port-Mapping | im Compose | per `kubectl port-forward` |