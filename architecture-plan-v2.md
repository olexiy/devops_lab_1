# Архитектурный план: Batch-обработка рейтингов клиентов

## Резюме проекта

**Задача:** Ежедневный ночной пересчёт рейтинга надёжности для 30–50 млн клиентских записей.  
**Источник:** Реляционная СУБД (database-agnostic, для тестирования — MySQL)  
**Цель:** PostgreSQL (результаты рейтинга)  
**SLA:** Ориентир ~2 часа, но главный приоритет — надёжность и оптимизация, а не жёсткий лимит  
**Стек:** Spring Batch 6.0, Spring Boot 4.0, Spring Framework 7, Java 21+  
**Оркестрация:** Argo Workflows (установка с нуля)  
**Инфраструктура:** Kubernetes (on-premise, только Helm Charts, без внешних сервисов)  
**Автоматизация:** Makefile для установки и повторного развёртывания всей инфраструктуры  
**Подход:** Все параметры, влияющие на производительность — вынесены в единый конфигурационный файл

---

## Часть 0: Ключевые архитектурные решения

### 0.1 Партиционирование без последовательных ID

Проблема: в банковской системе ID клиентов не обязательно последовательные — это могут быть UUID, хэши, или ID с пробелами (удалённые записи). Классический `ColumnRangePartitioner` с `MIN(id)`/`MAX(id)` создаст несбалансированные партиции.

**Решение: NTILE-based pre-scan**

На этапе partitioner (отдельный под в Argo) выполняем SQL-запрос:

```sql
-- Database-agnostic: NTILE поддерживается в MySQL 8+, PostgreSQL 8.4+, Oracle 8i+, MS SQL 2005+
SELECT partition_num, MIN(id) AS min_id, MAX(id) AS max_id, COUNT(*) AS record_count
FROM (
    SELECT id, NTILE(:partitionCount) OVER (ORDER BY id) AS partition_num
    FROM customers
) partitioned
GROUP BY partition_num
ORDER BY partition_num;
```

Этот запрос:
- Сортирует ВСЕ записи по ID (даже если они не последовательные)
- Делит их на N равных групп (NTILE гарантирует ±1 запись разницы между группами)
- Возвращает для каждой группы `min_id`, `max_id` и `record_count`
- `:partitionCount` — параметр из конфигурации

**Производительность:** Для 50 млн записей — 30–90 секунд (полный table scan с сортировкой по PRIMARY KEY). Выполняется однократно перед запуском worker'ов.

Выход partitioner'а — JSON-массив, который Argo Workflows использует для fan-out.

### 0.2 Вынесенные параметры оптимизации

Все параметры, которые можно менять для тюнинга, собраны в одном месте — ConfigMap в Kubernetes + параметры CronWorkflow:

```yaml
# === config/batch-tuning.yaml — единый конфиг оптимизации ===
apiVersion: v1
kind: ConfigMap
metadata:
  name: batch-tuning
  namespace: batch
data:
  # --- Партиционирование ---
  PARTITION_COUNT: "50"                # Количество партиций (пакетов работы)
  
  # --- Параллелизм (Argo уровень) ---
  MAX_PARALLEL_PODS: "6"              # Макс. одновременных worker-подов
  
  # --- Spring Batch параметры ---
  CHUNK_SIZE: "500"                   # Размер chunk (checkpoint каждые N записей)
  FETCH_SIZE: "500"                   # JDBC fetchSize (должен совпадать с chunk)
  VIRTUAL_THREADS: "80"              # Количество виртуальных потоков на под
  
  # --- Retry/Skip ---
  RETRY_LIMIT: "3"                    # Сколько раз повторить failed item
  SKIP_LIMIT: "100"                   # Макс. пропусков на партицию
  
  # --- Connection pools ---
  SOURCE_DB_POOL_SIZE: "5"           # HikariCP pool для source DB на под
  TARGET_DB_POOL_SIZE: "5"           # HikariCP pool для target DB на под
  JOBREPOSITORY_DB_POOL_SIZE: "3"    # HikariCP pool для JobRepository на под
  
  # --- Web Service ---
  WEBSERVICE_CONNECT_TIMEOUT_MS: "2000"
  WEBSERVICE_READ_TIMEOUT_MS: "5000"
  
  # --- JVM параметры (для worker подов) ---
  WORKER_JVM_OPTS: >-
    -XX:+UseZGC
    -XX:+ZGenerational
    -Xms1g -Xmx1536m
    -XX:+UseStringDeduplication
  
  # --- JVM параметры (для init/partitioner/report подов) ---  
  LIGHTWEIGHT_JVM_OPTS: >-
    -XX:+UseSerialGC
    -Xms256m -Xmx512m
  
  # --- Argo retry ---
  WORKER_RETRY_LIMIT: "2"            # Retry пода при crash
  WORKER_RETRY_BACKOFF: "30s"        # Backoff между retry
  
  # --- Мониторинг ---
  METRICS_PUSH_INTERVAL: "10s"       # Интервал push метрик в OTel Collector
  PROGRESS_UPDATE_INTERVAL: "10"     # Обновлять прогресс каждые N chunks
```

Изменение любого параметра: `kubectl apply -f config/batch-tuning.yaml` → перезапуск workflow → новые значения подхватываются.

### 0.3 Оркестрация: почему Argo Workflows

**Почему не KEDA+Kafka:**
- KEDA+Kafka вводит два компонента (Kafka cluster + KEDA operator), каждый из которых надо устанавливать через Helm, настраивать, мониторить, обслуживать
- Argo Workflows — один Helm chart, который даёт: DAG, визуализацию, retry, parallelism control, CronWorkflow, scale-to-zero из коробки
- `parallelism: N` в Argo автоматически ограничивает одновременные поды и ставит остальные в очередь — именно нужное поведение
- После завершения workflow — подов нет (scale-to-zero by design)

### 0.4 Архитектура приложения: Monorepo с отдельными модулями

**Решение: Multi-module Maven Monorepo, отдельный Docker image для каждого модуля.**

Аргументация:
- Init-под и Worker-под имеют совершенно разные зависимости. Worker тянет WebClient, Resilience4j, полный Spring Batch chunk processing. Init тянет Flyway, healthcheck-клиенты. Один fat JAR = все зависимости во всех подах, лишний размер образа, лишнее время старта.
- JVM startup tuning принципиально разный: worker нужен ZGC, большой heap, virtual threads. Init — SerialGC, минимальный heap, быстрый старт.
- В одном модуле с профилями всегда соблазн переиспользовать классы не по назначению — через полгода разделение размывается.
- Container image: worker ~200MB, init ~120MB. Меньший образ = быстрее pull.

```
batch-app/
├── pom.xml                    (parent POM)
├── batch-core/                ← Домен: entity, DTO, общие утилиты, DB config interfaces
│   └── src/main/java/
│       └── com/bank/rating/core/
│           ├── entity/        (Customer, CustomerRating, etc.)
│           ├── config/        (DataSource configs, общие properties)
│           └── util/          (ID partitioning utilities)
├── batch-init/                ← Init-под: Flyway, healthchecks, cleanup, стартовый отчёт
│   ├── src/main/java/
│   │   └── com/bank/rating/init/
│   ├── Dockerfile
│   └── pom.xml                (depends on batch-core)
├── batch-partitioner/         ← Partitioner-под: NTILE query → JSON stdout
│   ├── src/main/java/
│   │   └── com/bank/rating/partitioner/
│   ├── Dockerfile
│   └── pom.xml                (depends on batch-core)
├── batch-worker/              ← Worker-под: Spring Batch job, reader/processor/writer
│   ├── src/main/java/
│   │   └── com/bank/rating/worker/
│   │       ├── config/        (BatchConfig, StepConfig, ResilenceConfig)
│   │       ├── reader/        (CustomerItemReader)
│   │       ├── processor/     (RatingProcessor — веб-сервисы + вычисление)
│   │       ├── writer/        (RatingItemWriter — UPSERT)
│   │       ├── listener/      (ProgressListener, SkipListener, MetricsListener)
│   │       └── RatingWorkerApplication.java
│   ├── Dockerfile
│   └── pom.xml                (depends on batch-core)
└── batch-report/              ← Report-под: финальный отчёт, оповещение
    ├── src/main/java/
    │   └── com/bank/rating/report/
    ├── Dockerfile
    └── pom.xml                (depends on batch-core)
```

Каждый модуль — отдельный Spring Boot application с собственным `main()`, `application.yaml`, `Dockerfile`. Общий код — только в `batch-core`.

### 0.5 Database-agnostic подход

- Reader: используем стандартный `JdbcCursorItemReader` с ANSI SQL (`SELECT ... WHERE id BETWEEN ? AND ?`)
- Writer: UPSERT через Spring Batch `JdbcBatchItemWriter`. SQL для UPSERT — в `application.yaml` как property, разный для разных СУБД:
  - PostgreSQL: `INSERT ... ON CONFLICT (customer_id) DO UPDATE SET ...`
  - MySQL: `INSERT ... ON DUPLICATE KEY UPDATE ...`
  - Oracle: `MERGE INTO ... USING ... WHEN MATCHED THEN UPDATE WHEN NOT MATCHED THEN INSERT`
- Flyway: отдельные каталоги миграций по СУБД (`db/migration/mysql/`, `db/migration/postgresql/`)
- Partitioner NTILE: стандартная SQL оконная функция, поддерживается везде
- JobRepository: Spring Batch автоматически определяет диалект по DataSource
- Connection pool: HikariCP с параметрами из ConfigMap

---

## Часть 1: Подготовка ландшафта (Infrastructure Setup)

### 1.1 Структура проекта

```
batch-rating-project/
├── Makefile                          # Главный Makefile
├── config/
│   └── batch-tuning.yaml            # Единый конфиг оптимизации (ConfigMap)
├── infrastructure/
│   ├── Makefile
│   ├── k3d/
│   │   └── cluster-config.yaml
│   ├── helm-values/
│   │   ├── argo-workflows.yaml
│   │   ├── prometheus.yaml
│   │   ├── loki.yaml
│   │   ├── grafana-alloy.yaml
│   │   ├── otel-collector.yaml
│   │   ├── mysql.yaml
│   │   └── postgresql.yaml
│   ├── grafana-dashboards/
│   │   ├── spring-batch-overview.json
│   │   ├── batch-business-report.json
│   │   └── argo-workflows.json
│   └── namespaces.yaml
├── services/
│   ├── Makefile
│   ├── data-generator/               # Bash/Python скрипт генерации данных
│   │   ├── generate-data.sh          # Основной скрипт
│   │   └── k8s/
│   ├── mock-services/                # Мок веб-сервисы (Spring Boot app)
│   │   ├── pom.xml
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── k8s/
│   └── flyway/
│       ├── mysql/
│       │   └── V1__create_customers.sql
│       └── postgresql/
│           └── V1__create_ratings.sql
├── batch-app/                        # Monorepo — multi-module Maven
│   ├── pom.xml                       # Parent POM
│   ├── batch-core/
│   ├── batch-init/
│   ├── batch-partitioner/
│   ├── batch-worker/
│   ├── batch-report/
│   └── Makefile
├── workflow/
│   ├── Makefile
│   ├── templates/
│   │   ├── cron-workflow.yaml
│   │   └── rating-workflow.yaml
│   └── rbac/
│       └── workflow-rbac.yaml
└── docs/
    └── architecture.md
```

### 1.2 Makefile — верхний уровень

```makefile
.PHONY: all infra services batch workflow clean

# === Полная установка ===
all: infra services batch workflow
	@echo "✅ Полная установка завершена"

infra:
	$(MAKE) -C infrastructure all

services:
	$(MAKE) -C services all

batch:
	$(MAKE) -C batch-app all

workflow:
	$(MAKE) -C workflow all

# === Генерация тестовых данных ===
# Использование: make generate COUNT=50000000
generate:
	$(MAKE) -C services generate COUNT=$(COUNT)

clean:
	$(MAKE) -C workflow clean
	$(MAKE) -C batch-app clean
	$(MAKE) -C services clean
	$(MAKE) -C infrastructure clean

# Быстрый перезапуск (без пересоздания кластера)
redeploy: batch workflow
	@echo "✅ Переразвёртывание завершено"

# Применить изменения в конфиге оптимизации
config:
	kubectl apply -f config/batch-tuning.yaml
	@echo "✅ Конфигурация обновлена. Следующий запуск workflow подхватит новые значения."

# Запустить workflow вручную (не ждать cron)
run:
	argo submit -n batch workflow/templates/rating-workflow.yaml --watch

# Открыть Argo UI
argo-ui:
	kubectl port-forward svc/argo-workflows-server -n argo 2746:2746 &
	@echo "🌐 Argo UI: https://localhost:2746"

# Открыть Grafana
grafana:
	kubectl port-forward svc/monitoring-grafana -n monitoring 3000:80 &
	@echo "🌐 Grafana: http://localhost:3000"
```

### 1.3 Infrastructure Makefile

```makefile
# === infrastructure/Makefile ===
.PHONY: all cluster namespaces argo monitoring databases clean

CLUSTER_NAME := batch-rating
K3D_CONFIG := k3d/cluster-config.yaml

all: cluster namespaces databases monitoring argo config
	@echo "✅ Инфраструктура развёрнута"

cluster:
	@echo "🔧 Создание k3d кластера..."
	k3d cluster create $(CLUSTER_NAME) --config $(K3D_CONFIG) || true
	kubectl wait --for=condition=Ready nodes --all --timeout=120s

namespaces:
	kubectl apply -f namespaces.yaml

databases: namespaces
	@echo "🗄️ Установка MySQL (источник)..."
	helm repo add bitnami https://charts.bitnami.com/bitnami || true
	helm repo update
	helm upgrade --install mysql bitnami/mysql \
		--namespace databases --create-namespace \
		-f helm-values/mysql.yaml --wait --timeout 5m
	@echo "🗄️ Установка PostgreSQL (цель + JobRepository)..."
	helm upgrade --install postgresql bitnami/postgresql \
		--namespace databases \
		-f helm-values/postgresql.yaml --wait --timeout 5m

monitoring: namespaces
	@echo "📊 Установка kube-prometheus-stack..."
	helm repo add prometheus-community https://prometheus-community.github.io/helm-charts || true
	helm repo add grafana https://grafana.github.io/helm-charts || true
	helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts || true
	helm repo update
	helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
		--namespace monitoring --create-namespace \
		-f helm-values/prometheus.yaml --wait --timeout 5m
	helm upgrade --install loki grafana/loki \
		--namespace monitoring -f helm-values/loki.yaml --wait --timeout 5m
	helm upgrade --install alloy grafana/alloy \
		--namespace monitoring -f helm-values/grafana-alloy.yaml --wait --timeout 3m
	helm upgrade --install otel-collector open-telemetry/opentelemetry-collector \
		--namespace monitoring -f helm-values/otel-collector.yaml --wait --timeout 3m
	@echo "📊 Загрузка Grafana дашбордов..."
	kubectl create configmap grafana-dashboards-batch \
		--from-file=grafana-dashboards/ --namespace monitoring \
		--dry-run=client -o yaml | kubectl apply -f -

argo: namespaces
	@echo "🔄 Установка Argo Workflows..."
	helm repo add argo https://argoproj.github.io/argo-helm || true
	helm repo update
	helm upgrade --install argo-workflows argo/argo-workflows \
		--namespace argo --create-namespace \
		-f helm-values/argo-workflows.yaml --wait --timeout 5m

config:
	kubectl apply -f ../config/batch-tuning.yaml

clean:
	k3d cluster delete $(CLUSTER_NAME) || true
```

### 1.4 Ключевые Helm Values

**Argo Workflows (`helm-values/argo-workflows.yaml`):**
```yaml
server:
  enabled: true
  serviceType: ClusterIP
  extraArgs:
    - --auth-mode=server
controller:
  metricsConfig:
    enabled: true
    port: 9090
    path: /metrics
  workflowDefaults:
    spec:
      ttlStrategy:
        secondsAfterCompletion: 172800  # 48 часов
      podGC:
        strategy: OnWorkflowCompletion
```

**OpenTelemetry Collector (`helm-values/otel-collector.yaml`):**
```yaml
mode: daemonset
config:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318
  processors:
    batch:
      timeout: 10s
    k8sattributes:
      extract:
        metadata: [k8s.pod.name, k8s.namespace.name, k8s.node.name]
  exporters:
    prometheusremotewrite:
      endpoint: "http://monitoring-prometheus-server.monitoring:9090/api/v1/write"
  service:
    pipelines:
      metrics:
        receivers: [otlp]
        processors: [batch, k8sattributes]
        exporters: [prometheusremotewrite]
```

---

## Часть 2: Сервисы и данные

### 2.1 Схемы баз данных

**Источник (MySQL для тестов, database-agnostic миграции):**

```sql
-- flyway/source/V1__create_customers.sql
CREATE TABLE customers (
    id BIGINT PRIMARY KEY,
    customer_number VARCHAR(20) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE,
    registration_date DATE NOT NULL,
    customer_type VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    credit_score_internal DECIMAL(5,2),
    branch_code VARCHAR(10) NOT NULL
);

CREATE TABLE customer_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    transaction_date DATE NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    transaction_type VARCHAR(10) NOT NULL
);
CREATE INDEX idx_cust_tx_date ON customer_transactions(customer_id, transaction_date);

CREATE TABLE customer_products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    product_type VARCHAR(50) NOT NULL,
    open_date DATE NOT NULL,
    balance DECIMAL(15,2) DEFAULT 0
);
CREATE INDEX idx_cust_prod ON customer_products(customer_id);
```

**Цель (PostgreSQL):**

```sql
-- flyway/target/V1__create_ratings.sql
CREATE TABLE customer_ratings (
    customer_id BIGINT PRIMARY KEY,
    customer_number VARCHAR(20) NOT NULL,
    rating_score DECIMAL(5,2) NOT NULL,
    rating_class VARCHAR(5) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    calculated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    calculation_version VARCHAR(10) NOT NULL,
    avg_balance_12m DECIMAL(15,2),
    product_count INT,
    transaction_volume_12m DECIMAL(15,2),
    external_score DECIMAL(5,2),
    processing_pod VARCHAR(100),
    processing_duration_ms INT
);

CREATE TABLE rating_processing_dlq (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    error_message TEXT,
    error_class VARCHAR(200),
    retry_count INT DEFAULT 0,
    failed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    partition_id INT,
    pod_name VARCHAR(100)
);

CREATE TABLE rating_processing_progress (
    partition_id INT PRIMARY KEY,
    total_records INT NOT NULL,
    processed_records INT DEFAULT 0,
    skipped_records INT DEFAULT 0,
    failed_records INT DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    pod_name VARCHAR(100),
    status VARCHAR(20) DEFAULT 'PENDING'
);
```

### 2.2 Генератор тестовых данных — скрипт

Bash-скрипт, который:
- Принимает параметр: количество записей
- Очищает все старые данные в начале
- Генерирует случайных клиентов, собирая кусочки (имя, фамилия, дата рождения, branch) из предопределённых массивов
- Выводит прогресс в % в командную строку
- Использует MySQL `LOAD DATA LOCAL INFILE` или batch INSERT для скорости
- Запускается через Makefile: `make generate COUNT=50000000`

```makefile
# === services/Makefile (фрагмент) ===
generate:
	@echo "📦 Генерация $(COUNT) записей..."
	./data-generator/generate-data.sh $(COUNT)
```

Если bash-скрипт окажется слишком медленным для 50M записей (вероятно), переходим на Python с `mysql-connector` bulk insert или на Java CLI-tool в отдельном модуле `data-generator/`. Решим при реализации.

### 2.3 Мок веб-сервисы

Spring Boot REST-приложение (Deployment, 2 реплики):

**Endpoints:**
- `GET /api/v1/external-score/{customerId}` — случайный external credit score (50.0–99.9), задержка 30–70ms
- `GET /api/v1/average-balance/{customerId}` — случайная средняя сумма (1000–500000 EUR), задержка 20–50ms
- `GET /health` — healthcheck

Конфигурация через environment variables (задержка, error rate для тестирования retry).

---

## Часть 3: Spring Batch — multi-module Monorepo

### 3.1 Модульная структура

```
batch-app/
├── pom.xml                    (parent POM)
├── batch-core/                ← Домен и общие компоненты
│   └── com/bank/rating/core/
│       ├── entity/            Customer, CustomerRating, ProcessingProgress
│       ├── config/            DataSourceConfig, TuningProperties (из ConfigMap)
│       └── util/              PartitionRange DTO
├── batch-init/                ← Init-под
│   └── com/bank/rating/init/
│       ├── InitApplication.java
│       ├── FlywayValidator.java
│       ├── HealthChecker.java (проверка MySQL, PostgreSQL, mock-services)
│       └── DataCleaner.java   (очистка таблиц + создание progress записей)
├── batch-partitioner/         ← Partitioner-под
│   └── com/bank/rating/partitioner/
│       ├── PartitionerApplication.java
│       └── NtilePartitioner.java (NTILE query → JSON stdout)
├── batch-worker/              ← Worker-под (основная бизнес-логика)
│   └── com/bank/rating/worker/
│       ├── RatingWorkerApplication.java
│       ├── config/
│       │   ├── BatchJobConfig.java
│       │   ├── StepConfig.java
│       │   └── ResilienceConfig.java
│       ├── reader/
│       │   └── CustomerItemReader.java (JdbcCursorItemReader)
│       ├── processor/
│       │   └── RatingProcessor.java (веб-сервисы + вычисление)
│       ├── writer/
│       │   └── RatingItemWriter.java (UPSERT)
│       └── listener/
│           ├── ProgressListener.java (обновляет progress таблицу)
│           ├── SkipListener.java (пишет в DLQ)
│           └── MetricsListener.java (custom Micrometer метрики)
└── batch-report/              ← Report-под
    └── com/bank/rating/report/
        ├── ReportApplication.java
        └── ReportGenerator.java (финальный отчёт)
```

### 3.2 Worker — ключевые компоненты

**Reader:** `JdbcCursorItemReader` с ANSI SQL, `fetchSize` из ConfigMap.

**Processor:** Для каждого `Customer`:
1. Запрос к `customer_transactions` (агрегат за 12 мес)
2. Запрос к `customer_products` (количество, баланс)
3. HTTP вызов: `/api/v1/external-score/{id}`
4. HTTP вызов: `/api/v1/average-balance/{id}`
5. Вычисление рейтинга по формуле
6. Возврат `CustomerRating`

**Writer:** `JdbcBatchItemWriter` с UPSERT SQL (из property, database-specific).

**Fault tolerance:**
- `retryLimit` из ConfigMap (default: 3) для `WebServiceException`, `ConnectTimeoutException`
- `skipLimit` из ConfigMap (default: 100) для неретрайабельных ошибок → DLQ
- Resilience4j `CircuitBreaker` на веб-сервисы

### 3.3 JVM параметры — из ConfigMap

```dockerfile
# batch-worker/Dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY target/*.jar /app/app.jar
# JVM_OPTS приходит из ConfigMap → env переменная
ENTRYPOINT ["sh", "-c", "java $WORKER_JVM_OPTS -jar /app/app.jar $0 $@"]
```

Разные JVM параметры для разных типов подов:
- **Worker:** ZGC, 1–1.5GB heap, virtual threads
- **Init/Partitioner/Report:** SerialGC, 256–512MB heap, быстрый старт

### 3.4 Graceful Shutdown (Spring Batch 6.0)

Spring Batch 6.0 нативно поддерживает graceful shutdown: при SIGTERM текущий chunk завершается, step переходит в STOPPED, состояние сохраняется в JobRepository. Kubernetes `terminationGracePeriodSeconds` из ConfigMap (default: 120s).

### 3.5 Метрики (Push-based через OTLP)

Spring Boot 4.0 → `management.otlp.metrics.export.url` → OTel Collector DaemonSet → Prometheus.

Решает проблему эфемерных подов: под пушит метрики каждые N секунд (из ConfigMap), а не ждёт когда Prometheus его заскрейпит.

Custom метрики: `batch.rating.items.processed`, `batch.rating.items.failed`, `batch.rating.webservice.duration`, `batch.rating.partition.progress`.

---

## Часть 4: Argo Workflow — оркестрация

### 4.1 Workflow DAG

```
[CronWorkflow: ежедневно ночью]
         │
    ┌────▼────┐
    │  init   │  Flyway validation, healthchecks, cleanup, стартовый отчёт
    └────┬────┘
         │
  ┌──────▼──────┐
  │ partitioner │  NTILE query → JSON [{minId, maxId, partitionId, recordCount}, ...]
  └──────┬──────┘
         │ withParam (fan-out, parallelism из ConfigMap)
   ┌─────┼─────┐── ... ──┐
   ▼     ▼     ▼         ▼
 [w-0] [w-1] ... ...  [w-N]   Spring Batch workers (макс. N параллельных)
   └─────┼─────┘── ... ──┘
         │ depends: all workers
    ┌────▼────┐
    │ report  │  Финальный отчёт, статистика, оповещение
    └─────────┘
```

### 4.2 CronWorkflow YAML (параметризованный)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: CronWorkflow
metadata:
  name: nightly-rating-calculation
  namespace: batch
spec:
  schedule: "0 2 * * *"
  timezone: "Europe/Berlin"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 600
  workflowSpec:
    entrypoint: rating-pipeline
    parallelism: 6  # Берётся из ConfigMap при деплое через envsubst
    podGC:
      strategy: OnWorkflowCompletion
    ttlStrategy:
      secondsAfterCompletion: 172800
    serviceAccountName: batch-workflow-sa
    
    templates:
      - name: rating-pipeline
        dag:
          tasks:
            - name: init
              template: init-step
            - name: partitioner
              template: partitioner-step
              depends: "init"
            - name: workers
              template: worker-step
              depends: "partitioner"
              arguments:
                parameters:
                  - name: partition-data
                    value: "{{item}}"
              withParam: "{{tasks.partitioner.outputs.result}}"
            - name: report
              template: report-step
              depends: "workers"
      
      - name: init-step
        container:
          image: batch-init:latest
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: LIGHTWEIGHT_JVM_OPTS
          envFrom:
            - secretRef:
                name: db-credentials
            - configMapRef:
                name: batch-tuning
          resources:
            requests: { cpu: "500m", memory: "512Mi" }
            limits: { cpu: "1", memory: "1Gi" }
      
      - name: partitioner-step
        script:
          image: batch-partitioner:latest
          command: ["sh", "-c"]
          args:
            - "java $JAVA_OPTS -jar /app/app.jar"
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: LIGHTWEIGHT_JVM_OPTS
          envFrom:
            - secretRef:
                name: db-credentials
            - configMapRef:
                name: batch-tuning
          resources:
            requests: { cpu: "500m", memory: "512Mi" }
      
      - name: worker-step
        inputs:
          parameters:
            - name: partition-data
        retryStrategy:
          limit: 2  # из ConfigMap при деплое
          retryPolicy: OnFailure
          backoff:
            duration: "30s"
            factor: 2
        container:
          image: batch-worker:latest
          args:
            - "--partition.minId={{inputs.parameters.partition-data.minId}}"
            - "--partition.maxId={{inputs.parameters.partition-data.maxId}}"
            - "--partition.id={{inputs.parameters.partition-data.partitionId}}"
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: WORKER_JVM_OPTS
            - name: PARTITION_ID
              value: "{{inputs.parameters.partition-data.partitionId}}"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://otel-collector.monitoring:4318"
          envFrom:
            - secretRef:
                name: db-credentials
            - configMapRef:
                name: batch-tuning
          resources:
            requests: { cpu: "2", memory: "2Gi" }
            limits: { cpu: "2", memory: "2Gi" }
      
      - name: report-step
        container:
          image: batch-report:latest
          env:
            - name: JAVA_OPTS
              valueFrom:
                configMapKeyRef:
                  name: batch-tuning
                  key: LIGHTWEIGHT_JVM_OPTS
          envFrom:
            - secretRef:
                name: db-credentials
          resources:
            requests: { cpu: "500m", memory: "512Mi" }
```

### 4.3 Как работает parallelism

1. Partitioner генерирует JSON с N элементами (N = PARTITION_COUNT)
2. `withParam` создаёт N задач-worker'ов
3. Argo запускает первые `MAX_PARALLEL_PODS` задач
4. Когда worker завершается → следующий из очереди стартует немедленно
5. В любой момент работают максимум `MAX_PARALLEL_PODS` подов
6. После всех worker'ов → podGC удаляет поды → scale to zero

### 4.4 Retry при падении пода

Если worker-под падает (OOMKill, node failure):
1. Argo создаёт новый под с теми же параметрами
2. Spring Batch подключается к JobRepository, видит незавершённый StepExecution
3. Продолжает с последнего committed chunk (не с начала)
4. Уже записанные рейтинги перезаписываются через UPSERT — идемпотентно

---

## Часть 5: Мониторинг и визуализация

### 5.1 Три уровня

**Уровень 1: Argo Workflows UI**
- DAG в реальном времени: init → partitioner → workers → report
- Каждый worker — узел в DAG (Running=голубой, Succeeded=зелёный, Failed=красный)
- Клик → логи, параметры, время выполнения
- Timeline: когда каждый worker начал и закончил
- Подходит для демонстрации бизнесу

**Уровень 2: Grafana — технический дашборд**
- Job duration vs SLA
- Items/sec (суммарно и по подам)
- Per-partition duration (bar chart — видно bottlenecks)
- Chunk latency (p50, p95, p99)
- Web service latency
- Skip/retry counts
- JVM heap, GC
- Источник: Spring Boot OTLP → OTel Collector → Prometheus

**Уровень 3: Grafana — бизнес-отчёт**
- Gauge 0–100%: прогресс обработки
- ETA: расчётное время завершения
- Heatmap партиций по статусу
- Итого: обработано / ошибок / пропущено
- Источник: таблица `rating_processing_progress` через PostgreSQL datasource

### 5.2 Логирование

- Grafana Alloy (DaemonSet): собирает логи из `/var/log/pods` даже после удаления пода → Loki
- Structured logging с MDC: `partition.id`, `job.execution.id`, `step.name`

---

## Чеклист: о чём ещё помнить

- **Безопасность:** DB credentials в Kubernetes Secrets, RBAC, NetworkPolicy
- **Предотвращение перекрытия:** CronWorkflow `concurrencyPolicy: Forbid`
- **Connection pooling:** HikariCP параметры из ConfigMap. Для production — PgBouncer
- **Тестирование:** Unit (`@SpringBatchTest`), Integration (Testcontainers), Performance (1% данных)
- **Генерация данных:** Скрипт с параметром COUNT, очистка старых данных, прогресс в %
