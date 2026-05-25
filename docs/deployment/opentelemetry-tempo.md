# Searchess — OpenTelemetry and Tempo Distributed Tracing

**Status:** Infrastructure deployed; game-service (F1) and history-service (F2) instrumented; ai-service and python-ai-service are F3–F4  
**Date:** 2026-05-25  
**Scope:** All Kubernetes environments (base); university server GitOps overlay

---

## 1. Why traces alongside metrics

Prometheus and Grafana cover **metrics**: counters, gauges, histograms aggregated over time
(request rate, error rate, latency percentiles, JVM heap, Redis stream lag).

Metrics answer "how much / how often / how slow on average." They do not answer "why did
this specific request fail?" or "which call in this chain was slow?"

**Distributed traces** answer those questions. A trace follows a single request across
service boundaries — game-service calling history-service, history-service writing to
Postgres, ai-service proxying python-ai-service — and records the timing of each hop
as a span.

Prometheus remains the primary observability signal. Tempo is additive.

---

## 2. Architecture

```
Services (Scala / Python)
   │  OTLP/gRPC :4317  or  OTLP/HTTP :4318
   ▼
otel-collector (ClusterIP: otel-collector:4317, :4318)
   │  OTLP/gRPC → tempo:4317
   ▼
tempo (ClusterIP: tempo:3200 query, tempo:4317 OTLP recv)
   │  Tempo HTTP query API
   ▼
Grafana (existing) — Tempo datasource added
```

The **OpenTelemetry Collector** is the single ingestion point for all trace data from
services. It batches and forwards to Tempo. Services never connect to Tempo directly.

**Prometheus** continues to scrape `/metrics` endpoints independently. The two pipelines
do not interact.

---

## 3. Components added

### 3.1 Tempo

| Field | Value |
|---|---|
| Image | `grafana/tempo:2.6.1` |
| Namespace | `searchess` |
| Service name | `tempo` |
| HTTP query port | `3200` |
| OTLP/gRPC receive port | `4317` |
| Storage | `emptyDir` (traces lost on pod restart; see §7) |
| Retention | `24h` |
| Resources | requests: 64Mi / 50m CPU; limits: 256Mi / 200m CPU |

Config file: `deployment/k8s/base/tempo/configmap.yaml`

### 3.2 OpenTelemetry Collector

| Field | Value |
|---|---|
| Image | `otel/opentelemetry-collector-contrib:0.114.0` |
| Namespace | `searchess` |
| Service name | `otel-collector` |
| OTLP/gRPC port | `4317` |
| OTLP/HTTP port | `4318` |
| Health check port | `13133` |
| Resources | requests: 64Mi / 50m CPU; limits: 256Mi / 200m CPU |

The collector pipeline: `otlp receiver → batch processor → otlp exporter → Tempo`

Config file: `deployment/k8s/base/otel-collector/configmap.yaml`

### 3.3 Grafana Tempo datasource

Added `deployment/k8s/base/grafana/provisioning/datasources/tempo.yml` alongside the
existing `prometheus.yml`. Grafana loads all files from the datasources provisioning
directory; no changes to the Grafana Deployment were needed.

| Field | Value |
|---|---|
| Datasource name | `Tempo` |
| UID | `tempo` |
| Type | `tempo` |
| URL | `http://tempo:3200` |
| isDefault | `false` (Prometheus remains default) |

---

## 4. Files changed

| File | Change |
|---|---|
| `deployment/k8s/base/tempo/configmap.yaml` | New — Tempo server config |
| `deployment/k8s/base/tempo/deployment.yaml` | New — Tempo Deployment |
| `deployment/k8s/base/tempo/service.yaml` | New — Tempo ClusterIP Service |
| `deployment/k8s/base/otel-collector/configmap.yaml` | New — OTel Collector config |
| `deployment/k8s/base/otel-collector/deployment.yaml` | New — OTel Collector Deployment |
| `deployment/k8s/base/otel-collector/service.yaml` | New — OTel Collector ClusterIP Service |
| `deployment/k8s/base/grafana/provisioning/datasources/tempo.yml` | New — Grafana Tempo datasource |
| `deployment/k8s/base/kustomization.yaml` | Modified — 6 new resources + tempo.yml in grafana-datasources |

No application code changed. No overlay-specific changes required. All three overlays
(`uni-server-registry`, `local-k3d`, `uni-server-k3d`) inherit these resources from base.

---

## 5. What is active vs. inactive at this milestone

| Capability | Status |
|---|---|
| Tempo pod running | Active |
| OTel Collector pod running | Active |
| Grafana Tempo datasource provisioned | Active |
| Trace data in Grafana | **Active** — game-service and history-service emit spans |
| game-service traces | **Active (F1)** — Java agent auto-instrumentation; HTTP server, JDBC, Redis, outbound HTTP auto-instrumented |
| history-service traces | **Active (F2)** — Java agent auto-instrumentation; JDBC and Redis Streams consumer auto-instrumented |
| ai-service traces | **F3** — not yet instrumented |
| python-ai-service traces | **F4** — not yet instrumented |

Grafana → Explore → Tempo → service name `searchess-game-service` or
`searchess-history-service` will show spans once each service has handled at least one
request. Cross-service trace continuity (game-service → history-service span linkage)
requires both sides to propagate the W3C `traceparent` header — the Java agent handles
this automatically for HTTP calls, but the Redis Streams path does not carry trace
context natively. ai-service and python-ai-service do not appear in Tempo yet.

---

## 6. Service instrumentation — per-service status

### 6.1 game-service — F1 (complete)

**Approach:** OpenTelemetry Java agent auto-instrumentation. No application code changes.
The agent intercepts http4s server/client, PostgreSQL JDBC, and Redis at runtime.

**Dockerfile change** (`Dockerfile` — repo root, game-service):

```dockerfile
FROM eclipse-temurin:21-jre AS otel-agent
ARG OTEL_AGENT_VERSION=2.10.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

# In the runtime stage:
COPY --from=otel-agent /opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
```

**Kubernetes config** (`deployment/k8s/base/game-service/configmap.yaml`):

```yaml
JAVA_TOOL_OPTIONS: "-javaagent:/app/opentelemetry-javaagent.jar"
OTEL_SERVICE_NAME: "searchess-game-service"
OTEL_TRACES_EXPORTER: "otlp"
OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector:4317"
OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
```

The game-service Deployment already uses `envFrom: configMapRef: game-service-env`,
so these values are picked up without any Deployment manifest change.

**What is auto-instrumented (no manual spans needed):**

| Instrumentation | Auto-detected |
|---|---|
| Incoming HTTP requests | http4s server routes — each request becomes a root span |
| Outgoing HTTP calls | ai-service proxy call, history-service forward |
| PostgreSQL JDBC | All game schema reads and writes |
| Redis | `history.archives` Redis Streams publish |

**Validation:**

```bash
# After deploy, generate a request:
curl http://localhost:10000/health
# or start a game move through the API

# Then in Grafana → Explore → Tempo:
# Select service: searchess-game-service
# Traces should appear within a few seconds
```

**No manual spans were added.** The agent auto-instruments all framework and client
calls. Custom spans for business logic (e.g., move validation, check detection) are
a later, optional step.

---

### 6.2 history-service — F2 (complete)

Same pattern as F1. `Dockerfile.history` gets the same `otel-agent` download stage
(v2.10.0) and `COPY --from=otel-agent`. `deployment/k8s/base/history-service/configmap.yaml`
receives the OTel env vars.

```yaml
JAVA_TOOL_OPTIONS: "-javaagent:/app/opentelemetry-javaagent.jar"
OTEL_SERVICE_NAME: "searchess-history-service"
OTEL_TRACES_EXPORTER: "otlp"
OTEL_METRICS_EXPORTER: "none"
OTEL_LOGS_EXPORTER: "none"
OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector:4317"
OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
```

**What is auto-instrumented:**

| Instrumentation | Auto-detected |
|---|---|
| Incoming HTTP requests | http4s server health and ingest endpoints |
| PostgreSQL JDBC | All `history.history_archives` reads and writes |
| Redis Streams consumer | `XREADGROUP` calls on `searchess.history.archives` |

**Cross-service trace continuity note:** The game-service → history-service delivery
path uses Redis Streams, not HTTP. The Java agent auto-propagates W3C `traceparent`
headers on HTTP calls but not through Redis message envelopes. Each service will show
its own independent trace trees in Grafana Tempo rather than a single end-to-end trace
for the game → history path. HTTP-based call chains (e.g., game-service calling
ai-service over HTTP) will show as a single linked trace once F3 is complete.

---

### 6.3 ai-service — F3 (pending)

Same approach: `Dockerfile.ai` + `deployment/k8s/base/ai-service/configmap.yaml`.

```yaml
JAVA_TOOL_OPTIONS: "-javaagent:/app/opentelemetry-javaagent.jar"
OTEL_SERVICE_NAME: "searchess-ai-service"
OTEL_TRACES_EXPORTER: "otlp"
OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector:4317"
OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
```

The agent will auto-instrument the outbound HTTP call to python-ai-service.

---

### 6.4 python-ai-service — F4 (pending)

The Python service lives in a separate repo (`arutepsu/searchess-ai-service`) and uses
the Python OTel SDK rather than the Java agent.

```python
# requirements.txt additions:
opentelemetry-sdk
opentelemetry-exporter-otlp-proto-grpc
opentelemetry-instrumentation-fastapi  # if using FastAPI
opentelemetry-instrumentation-requests
```

```python
# In application startup:
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter

provider = TracerProvider()
provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter())
)
trace.set_tracer_provider(provider)
```

Set `OTEL_SERVICE_NAME=searchess-python-ai-service` and
`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317` in the service's Kubernetes
ConfigMap. Because this service code lives in a separate repo, its image is only rebuilt
via `workflow_dispatch` with `rebuild_python_ai=true`.

---

### 6.5 Validation after each service is instrumented

1. Rebuild and deploy the service (CI push or `workflow_dispatch`).
2. Trigger at least one chess game request through Envoy.
3. Open Grafana → Explore → select **Tempo** datasource.
4. Search by service name (e.g., `searchess-game-service`).
5. A trace should appear within a few seconds showing spans for each instrumented hop.

As more services are instrumented (F2, F3), traces will show cross-service call chains:
game-service → history-service, game-service → ai-service → python-ai-service.

---

## 7. Known limitations

| Limitation | Impact | Resolution |
|---|---|---|
| Tempo uses `emptyDir` storage | Traces lost on Tempo pod restart | Add a PersistentVolume when trace durability is needed |
| Partial service instrumentation | game-service and history-service emit spans; ai-service and python-ai-service not yet instrumented | F3–F4 instrumentation |
| No manual business-logic spans | Agent auto-instruments framework calls only; move validation, AI decision spans are absent | Add manual spans with OTel SDK after auto-instrumentation is validated |
| Retention set to 24h | Old traces expire automatically | Increase `block_retention` in tempo-config ConfigMap |
| Local k3d images must be imported | `grafana/tempo:2.6.1` and `otel/opentelemetry-collector-contrib:0.114.0` must be pulled or imported for local-k3d | `k3d image import grafana/tempo:2.6.1 otel/opentelemetry-collector-contrib:0.114.0` |

---

## 8. Resource budget impact (university VM — 4 GB)

| Component | Memory request | Memory limit | CPU request | CPU limit |
|---|---|---|---|---|
| tempo | 64 Mi | 256 Mi | 50m | 200m |
| otel-collector | 64 Mi | 256 Mi | 50m | 200m |
| **Total added** | **128 Mi** | **512 Mi** | **100m** | **400m** |

At zero traffic the two pods will idle well within their request floor. Burst usage
scales with trace volume, which is zero until Phase 2 instrumentation is active.

---

## 9. Port reference

| Service | DNS name | Port | Protocol | Purpose |
|---|---|---|---|---|
| otel-collector | `otel-collector` | 4317 | gRPC | OTLP/gRPC receive from services |
| otel-collector | `otel-collector` | 4318 | HTTP | OTLP/HTTP receive from services |
| tempo | `tempo` | 4317 | gRPC | OTLP/gRPC receive from otel-collector |
| tempo | `tempo` | 3200 | HTTP | Query API (Grafana datasource) |

All services are ClusterIP — not externally reachable.

---

## 10. Argo CD behavior

These resources are added to base and therefore appear in the `uni-server-registry` overlay
that Argo CD watches. On the next sync, Argo CD will:

1. Create the `tempo-config` ConfigMap.
2. Create the `otel-collector-config` ConfigMap.
3. Create the `tempo` Deployment and Service.
4. Create the `otel-collector` Deployment and Service.
5. Update the `grafana-datasources` ConfigMap (adds `tempo.yml` key).
6. Grafana will reload datasources from the updated ConfigMap on next restart or hot-reload.

Argo CD `prune: false` means no existing resources are deleted. `selfHeal: true` means
any manual drift is corrected.

To trigger a Grafana datasource reload without restarting the pod:
```sh
# Port-forward Grafana and call the reload API (requires admin credentials)
kubectl port-forward svc/grafana 3000:3000 -n searchess
curl -X POST -u admin:<password> http://localhost:3000/api/admin/provisioning/datasources/reload
```
