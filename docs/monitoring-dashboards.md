# Monitoring: Dashboards and Cluster Metrics

## Stack

Installed via `./scripts/install-monitoring.ps1`. All components run in the `monitoring` namespace.

| Component | Role | Address (port-forward) |
|---|---|---|
| Prometheus | Metrics scraping + TSDB storage | http://localhost:9090 |
| Alertmanager | Alert routing | http://localhost:9093 |
| Grafana | Visualization | http://localhost:3000 (admin / admin) |
| Pushgateway | Push-based metrics for short-lived batch pods | http://localhost:9091 |
| Loki | Log aggregation (Promtail → Loki) | http://localhost:3100 |
| Tempo | Distributed traces (OTLP → Tempo) | http://localhost:3200 |
| OTel Collector | Receives OTLP, fans out to Prometheus/Loki/Tempo | localhost:4317 (gRPC), :4318 (HTTP) |

If a port-forward drops, restart the script:
```powershell
./scripts/install-monitoring.ps1
```

---

## How Grafana Receives Dashboards

kube-prometheus-stack includes a sidecar container `grafana-sc-dashboard` (`kiwigrid/k8s-sidecar` image).
It watches **all** namespaces for ConfigMaps with the label:

```yaml
labels:
  grafana_dashboard: "1"
```

When it detects such a ConfigMap, it copies the JSON file into `/tmp/dashboards/` inside the Grafana pod
and calls `POST /api/admin/provisioning/dashboards/reload`. The dashboard appears in the UI within ~30 seconds.

### How to Add a Custom Dashboard

1. Export the JSON from Grafana UI (Share → Export → Save to file) or download from grafana.com.

2. **If downloaded from grafana.com — fix the datasource references first.**
   Dashboards from grafana.com contain an `__inputs` block at the top with placeholder variables
   like `${DS_PROMETHEUS}` or `${DS_THANOS}`. Grafana substitutes these during a normal UI import
   (it shows a "select datasource" dialog). When loading via ConfigMap sidecar, no substitution
   happens — all panels show `!` errors and "No data".

   Check what inputs the dashboard has:
   ```bash
   head -30 dashboard.json   # look for "__inputs" block
   ```
   Then replace the placeholder with the actual datasource uid. Our Prometheus uid is `prometheus`:
   ```bash
   sed 's/\${DS_PROMETHEUS}/prometheus/g' dashboard.json > dashboard-fixed.json
   sed 's/\${DS_THANOS}/prometheus/g'    dashboard.json > dashboard-fixed.json
   ```
   To find the uid of any datasource: Grafana UI → Connections → Data sources → click the source → check the URL (`/datasources/edit/<uid>`), or:
   ```bash
   curl -s http://admin:admin@localhost:3000/api/datasources | grep -o '"uid":"[^"]*"\|"name":"[^"]*"'
   ```

3. Create a file `monitoring/dashboards/<name>.cm.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: my-dashboard
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  my-dashboard.json: |
    { ... JSON ... }
```

3. Apply: `kubectl apply -f monitoring/dashboards/my-dashboard.cm.yaml`

The sidecar picks it up automatically — no Grafana restart needed.

### How to Update a Dashboard

Edit the JSON in the ConfigMap file and re-apply:

```bash
kubectl apply -f monitoring/dashboards/my-dashboard.cm.yaml
```

Grafana reloads within ~30 seconds. If you edited the dashboard in the Grafana UI first,
export it again (Share → Export) to keep the file in sync with what's in the cluster.

> **Note:** ConfigMaps larger than 256 KB must use server-side apply to avoid the
> `last-applied-configuration` annotation size limit:
> ```bash
> kubectl apply --server-side -f monitoring/dashboards/node-exporter-full.cm.yaml
> ```

### How to Write a Grafana Dashboard JSON

A dashboard is a single JSON object. The key fields:

```json
{
  "title": "My Dashboard",
  "uid": "my-dashboard-v1",        // unique identifier — used in URLs and links
  "refresh": "30s",                // auto-refresh interval
  "time": { "from": "now-1h", "to": "now" },
  "panels": [ ... ]
}
```

Each panel in `panels[]`:

```json
{
  "id": 1,
  "type": "stat",                  // stat | gauge | timeseries | table | row | text
  "title": "CPU Usage",
  "gridPos": { "x": 0, "y": 0, "w": 6, "h": 4 },  // grid is 24 columns wide
  "datasource": { "type": "prometheus", "uid": "" },
  "targets": [
    {
      "expr": "rate(node_cpu_seconds_total{mode!=\"idle\"}[5m]) * 100",
      "legendFormat": "CPU %",
      "refId": "A"
    }
  ],
  "fieldConfig": {
    "defaults": {
      "unit": "percent",
      "min": 0, "max": 100,
      "thresholds": {
        "mode": "absolute",
        "steps": [
          { "color": "green", "value": null },
          { "color": "yellow", "value": 60 },
          { "color": "red", "value": 85 }
        ]
      }
    }
  }
}
```

**Panel types:**
- `stat` — single large value with colored background
- `gauge` — semicircular indicator, good for percentages (CPU, memory, connections)
- `timeseries` — time series line chart
- `row` — section separator/header
- `table` — tabular data

**Grid:** 24 columns wide, rows are stacked vertically. `w` and `h` are in grid units.

---

## Installed Dashboards

### Built-in (kube-prometheus-stack, automatic)

| Path in Grafana | What it shows |
|---|---|
| Kubernetes / Compute Resources / Cluster | Total CPU/Memory across the cluster |
| Kubernetes / Compute Resources / Namespace (Pods) | CPU/Memory per namespace broken down by pod |
| Kubernetes / Compute Resources / Node (Pods) | CPU/Memory per node |
| Kubernetes / Compute Resources / Pod | Single pod details |
| Kubernetes / Networking / Cluster | Cluster network traffic |
| Node Exporter / USE Method / Cluster | CPU Utilization/Saturation/Errors cluster-wide |
| Node Exporter / USE Method / Node | Same metrics per node |
| Prometheus / Overview | Prometheus health (scrape stats, TSDB size) |
| Alertmanager / Overview | Alert queue |

### Added Manually

#### Node Exporter Full (grafana.com ID 1860)
File: `monitoring/dashboards/node-exporter-full.cm.yaml`

Detailed host-level monitoring. Unlike the built-in USE dashboards, shows:
- CPU: breakdown by mode (user / system / iowait / steal / nice)
- Memory: breakdown by type (buffers / cache / swap)
- Disk I/O: IOPS, throughput, latency per device
- Network: traffic per interface (receive / transmit bytes/packets/errors)
- System: load average, context switches, interrupts, open file descriptors, uptime

Use for diagnosing OS-level bottlenecks.

#### Argo Workflows Metrics (grafana.com ID 21393)
File: `monitoring/dashboards/argo-workflows.cm.yaml`

Official Argo Workflows dashboard for v3.6+, running on v4.0.3. Shows:
- Active / completed / failed workflows
- Workflow and step execution times
- Workflow controller queue
- Error and retry events

Metrics come from `workflow-controller` (port 9090). Prometheus scraping is configured
via `monitoring/argo-metrics-svc.yaml` + `monitoring/argo-metrics-sm.yaml`.

#### MySQL Overview (grafana.com ID 7362)
File: `monitoring/dashboards/mysql-overview.cm.yaml`

Metrics from `mysql-exporter` (prometheus-community/prometheus-mysql-exporter 2.13.0). Shows:
- Up/down status, uptime
- Connections (active / max)
- Queries per second, slow queries
- InnoDB buffer pool hit ratio

#### PostgreSQL Overview (grafana.com ID 9628)
File: `monitoring/dashboards/postgres-overview.cm.yaml`

Metrics from `postgres-exporter` (prometheus-community/prometheus-postgres-exporter 7.5.2). Shows:
- Up/down status
- Active connections, locks
- Transaction rate (commits / rollbacks)
- Table and index cache hit ratio

#### Project Overview (custom)
File: `monitoring/dashboards/project-overview.cm.yaml`

Hand-written custom dashboard. Single-page view of the entire project:
- **Cluster Health:** node count, running/failing pods, CPU% gauge, memory% gauge
- **Argo Workflows:** running / succeeded / failed / workers busy
- **Argo CD:** server UP, app controller UP, synced apps, out-of-sync apps
- **Databases:** MySQL UP + connections% + QPS / PostgreSQL UP + connections% + cache hit%

---

## Connecting Argo Workflows Metrics to Prometheus

By default `workflow-controller` exposes metrics on pod port `9090`,
but no Service or ServiceMonitor existed — Prometheus could not scrape it.

**`monitoring/argo-metrics-svc.yaml`** — headless Service in the `argo` namespace:
```yaml
selector:
  app: workflow-controller
ports:
  - name: metrics
    port: 9090
    targetPort: metrics
```

**`monitoring/argo-metrics-sm.yaml`** — ServiceMonitor in the `monitoring` namespace:
```yaml
labels:
  release: kube-prometheus-stack   # required — Prometheus instance selects SMs by this label
namespaceSelector:
  matchNames: [argo]
endpoints:
  - port: metrics
    scheme: https                  # Argo v4 serves metrics over TLS (self-signed)
    tlsConfig:
      insecureSkipVerify: true
```

**Verify:** Prometheus UI → Status → Targets → `argo/argo-workflow-controller-metrics/0` → **UP**

---

## Connecting ArgoCD Metrics to Prometheus

ArgoCD exposes two metrics endpoints:
- `argocd-metrics:8082` — application controller (app health, sync status, `argocd_app_info`)
- `argocd-server-metrics:8083` — API server

Both are covered by `monitoring/argocd-metrics-sm.yaml`.

---

## Metrics Sources Summary

| Source | How it reaches Prometheus | Metric prefix |
|---|---|---|
| Kubernetes nodes | node-exporter DaemonSet → ServiceMonitor | `node_*` |
| Kubernetes objects | kube-state-metrics → ServiceMonitor | `kube_*` |
| kubelet / cAdvisor | ServiceMonitor on kubelet | `container_*`, `kubelet_*` |
| Argo Workflows | ServiceMonitor (added) | `argo_workflows_*` |
| Argo CD | ServiceMonitor (added) | `argocd_*` |
| MySQL | mysql-exporter → ServiceMonitor | `mysql_*` |
| PostgreSQL | postgres-exporter → ServiceMonitor | `pg_*` |
| Batch pods (Spring Batch) | Pushgateway push → additionalScrapeConfig | `batch_*`, Spring Micrometer |
| Spring Boot services (future) | OTLP → OTel Collector → Prometheus remote_write | `http_*`, JVM metrics |

---

## Datasources in Grafana

| Name | Type | URL | Description |
|---|---|---|---|
| Prometheus (default) | prometheus | http://kube-prometheus-stack-prometheus:9090 | All metrics |
| Loki | loki | http://loki:3100 | Pod logs (Promtail → Loki) |
| Tempo | tempo | http://tempo:3200 | Distributed traces (OTLP) |

Loki is linked to Tempo via `derivedFields` — the `traceId` field in a log line becomes a link to the trace.
Tempo is linked to Prometheus via service map and to Loki via `tracesToLogsV2`.
