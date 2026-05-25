# Searchess — OpenTelemetry and Tempo Distributed Tracing

**Status:** Infrastructure deployed; service instrumentation is Phase 2  
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
| Tempo pod running | Active (once synced) |
| OTel Collector pod running | Active (once synced) |
| Grafana Tempo datasource provisioned | Active |
| Trace data in Grafana | **Inactive** — no service emits spans yet |
| game-service traces | **Phase 2** — requires OTel SDK |
| history-service traces | **Phase 2** — requires OTel SDK |
| ai-service traces | **Phase 2** — requires OTel SDK |
| python-ai-service traces | **Phase 2** — requires OTel SDK |

Opening Grafana → Explore → Tempo will show an empty trace list. This is expected.
The infrastructure is ready to receive spans the moment any service starts emitting them.

---

## 6. Phase 2 — Instrumenting services

When ready to add trace data, each service needs:

### Environment variables (same for all services)

```
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
OTEL_TRACES_EXPORTER=otlp
```

Per-service values:

| Service | OTEL_SERVICE_NAME |
|---|---|
| game-service | `searchess-game-service` |
| history-service | `searchess-history-service` |
| ai-service | `searchess-ai-service` |
| python-ai-service | `searchess-python-ai-service` |

Add these to the respective `configmap.yaml` under `deployment/k8s/base/<service>/`.

### SDK dependencies

**Scala services (game-service, history-service, ai-service):**

Add the OpenTelemetry Java agent as a JVM argument. No application code changes needed
for auto-instrumentation of http4s, JDBC, and Redis clients:

```dockerfile
# In the service Dockerfile, download the agent:
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# Add to JVM args:
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"
```

Or use `sbt-native-packager` scriptClasspath to include the agent. The Java agent
auto-instruments http4s server/client, PostgreSQL JDBC, and Redis at runtime without
any code changes to the application.

**Python AI service:**

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

With `OTEL_EXPORTER_OTLP_ENDPOINT` set in the environment, the exporter resolves the
endpoint automatically.

### Validation after Phase 2

1. Trigger a chess game request.
2. Open Grafana → Explore → select **Tempo** datasource.
3. Search by service name (`searchess-game-service`).
4. A trace should appear showing spans across game-service, history-service, and redis.

---

## 7. Known limitations

| Limitation | Impact | Resolution |
|---|---|---|
| Tempo uses `emptyDir` storage | Traces lost on Tempo pod restart | Add a PersistentVolume when trace durability is needed |
| No service emits spans yet | Grafana Tempo shows empty results | Phase 2 instrumentation required |
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
