# Мониторинг: дашборды и метрики кластера

## Стек

Установлен через `./scripts/install-monitoring.ps1`. Все компоненты в namespace `monitoring`.

| Компонент | Роль | Адрес (port-forward) |
|---|---|---|
| Prometheus | Скрапирует метрики, хранит TSDB | http://localhost:9090 |
| Alertmanager | Маршрутизация алертов | http://localhost:9093 |
| Grafana | Визуализация | http://localhost:3000 (admin / admin) |
| Pushgateway | Push-метрики для batch pods | http://localhost:9091 |
| Loki | Агрегация логов (Promtail → Loki) | http://localhost:3100 |
| Tempo | Distributed traces (OTLP → Tempo) | http://localhost:3200 |
| OTel Collector | Принимает OTLP, раздаёт в Prometheus/Loki/Tempo | localhost:4317 (gRPC), :4318 (HTTP) |

Если port-forward отвалился — перезапустить скрипт:
```powershell
./scripts/install-monitoring.ps1
```

---

## Как Grafana получает дашборды

В kube-prometheus-stack включён sidecar-контейнер `grafana-sc-dashboard` (образ `kiwigrid/k8s-sidecar`).
Он следит за **всеми** namespace кластера и ищет ConfigMap-ы с лейблом:

```yaml
labels:
  grafana_dashboard: "1"
```

При обнаружении ConfigMap sidecar копирует JSON-файл в `/tmp/dashboards/` внутри Grafana-пода
и вызывает `POST /api/admin/provisioning/dashboards/reload`. Дашборд появляется в UI через ~30 секунд.

### Как добавить свой дашборд

1. Экспортируй JSON из Grafana UI (Share → Export → Save to file) или скачай с grafana.com.
2. Создай файл `monitoring/dashboards/<name>.cm.yaml`:

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

3. Применить: `kubectl apply -f monitoring/dashboards/my-dashboard.cm.yaml`

---

## Установленные дашборды

### Встроенные (kube-prometheus-stack, автоматически)

| Путь в Grafana | Что показывает |
|---|---|
| Kubernetes / Compute Resources / Cluster | Суммарный CPU/Memory по всему кластеру |
| Kubernetes / Compute Resources / Namespace (Pods) | CPU/Memory по namespace с разбивкой по podам |
| Kubernetes / Compute Resources / Node (Pods) | CPU/Memory по ноде |
| Kubernetes / Compute Resources / Pod | Детали одного пода |
| Kubernetes / Networking / Cluster | Сетевой трафик кластера |
| Node Exporter / USE Method / Cluster | CPU Utilization/Saturation/Errors по кластеру |
| Node Exporter / USE Method / Node | То же по отдельной ноде |
| Prometheus / Overview | Состояние самого Prometheus (scrape stats, TSDB size) |
| Alertmanager / Overview | Очередь алертов |

### Добавленные вручную

#### Node Exporter Full (grafana.com ID 1860)
Файл: `monitoring/dashboards/node-exporter-full.cm.yaml`

Детальный мониторинг хост-машины. В отличие от встроенных USE-дашбордов, показывает:
- CPU: breakdown по режимам (user / system / iowait / steal / nice)
- Memory: детали по типам (buffers / cache / swap)
- Disk I/O: IOPS, throughput, latency по каждому устройству
- Network: трафик по каждому сетевому интерфейсу (receive / transmit bytes/packets/errors)
- System: load average, context switches, interrupts, open file descriptors, uptime

Используй для диагностики узких мест на уровне ОС.

#### Argo Workflows Metrics (grafana.com ID 21393)
Файл: `monitoring/dashboards/argo-workflows.cm.yaml`

Официальный дашборд Argo Workflows для v3.6+. Установлен на v4.0.3. Показывает:
- Активные / завершённые / упавшие workflow
- Время выполнения workflow и шагов
- Очередь workflow-controller
- Ошибки и retry-события

> Метрики поступают из `workflow-controller` (порт 9090). Подключение к Prometheus
> настроено через `monitoring/argo-metrics-svc.yaml` + `monitoring/argo-metrics-sm.yaml`.

---

## Подключение метрик Argo Workflows к Prometheus

По умолчанию `workflow-controller` экспозирует метрики на pod-порту `9090`,
но Service и ServiceMonitor отсутствовали — Prometheus их не скрапировал.

### Что добавлено

**`monitoring/argo-metrics-svc.yaml`** — headless Service в namespace `argo`:
```yaml
selector:
  app: workflow-controller
ports:
  - name: metrics
    port: 9090
    targetPort: metrics
```

**`monitoring/argo-metrics-sm.yaml`** — ServiceMonitor в namespace `monitoring`:
```yaml
labels:
  release: kube-prometheus-stack   # обязательно — Prometheus отбирает SM по этому лейблу
namespaceSelector:
  matchNames: [argo]
endpoints:
  - port: metrics
    interval: 30s
```

### Проверка

Через ~1 минуту после apply:
- Prometheus UI → Status → Targets → найти `argo/argo-workflow-controller-metrics/0`, статус **UP**
- Prometheus query: `argo_workflows_count` или `argo_workflow_status_phase`

---

## Источники метрик

| Источник | Как попадает в Prometheus | Метрики |
|---|---|---|
| Kubernetes nodes | node-exporter DaemonSet → ServiceMonitor | `node_*` |
| Kubernetes objects | kube-state-metrics → ServiceMonitor | `kube_*` |
| kubelet / cAdvisor | ServiceMonitor на kubelet | `container_*`, `kubelet_*` |
| Argo Workflows | ServiceMonitor (добавлен) | `argo_*` |
| Batch pods (Spring Batch) | Pushgateway push → additionalScrapeConfig | `batch_*`, Spring Micrometer metrics |
| Spring Boot сервисы (будущее) | OTLP → OTel Collector → Prometheus remote_write | `http_*`, JVM metrics, custom |

---

## Datasources в Grafana

| Name | Type | URL | Описание |
|---|---|---|---|
| Prometheus (default) | prometheus | http://kube-prometheus-stack-prometheus:9090 | Все метрики |
| Loki | loki | http://loki:3100 | Логи подов (Promtail → Loki) |
| Tempo | tempo | http://tempo:3200 | Distributed traces (OTLP) |

Loki связан с Tempo через `derivedFields` — из поля `traceId` в логе строится ссылка на трейс.
Tempo связан с Prometheus через service map и с Loki через `tracesToLogsV2`.
