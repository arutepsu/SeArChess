# Searchess Performance Workbench

## Overview

The Searchess Performance Workbench is an in-repo performance tool for running, analyzing, and browsing performance-test evidence for the Searchess backend.

It currently supports k6 load tests. Gatling simulations and JMH benchmarks are planned as future extensions.

## Location

```powershell
tools/performance/analysis/
```

Commands are normally run from this directory.

## Quick Start

```powershell
cd tools/performance/analysis
npm install
npm run build
npm run perf -- start
```

## Configuration

The workbench reads an optional repository-root `performance.config.json`.

Example:

```json
{
  "baseUrl": "http://localhost:10000/api",
  "outputRoot": "docs/performance",
  "defaultPhase": "baseline",
  "cpuUsagePercent": 72,
  "memoryUsagePercent": 61
}
```

`outputRoot` is resolved relative to the repository root/config file location, not relative to `tools/performance/analysis`.

## Interactive Workbench

Start the interactive workbench:

```powershell
npm run perf -- start
```

Menu areas:

- k6 Performance Tests
- Reports & History
- Settings
- Environment Check
- Gatling Simulations
- JMH Benchmarks

Gatling and JMH are placeholders for future integration.

Interactive k6 runs:

- use quiet/log output
- show spinner and progress feedback
- write raw k6 output to log files
- create immutable run folders
- can be browsed later via Reports & History

## Scriptable Commands

Run a configured k6 test:

```powershell
npm run perf -- k6 --test load
```

Run with explicit settings:

```powershell
npm run perf -- k6 --test stress --base-url http://localhost:10000/api --cpu 72 --memory 61 --phase baseline
```

Supported tests:

- `baseline`: small baseline smoke/load profile
- `load`: normal expected load
- `spike`: sudden traffic spike
- `stress`: high-concurrency pressure test

Run the full k6 suite:

```powershell
npm run perf -- k6-suite
```

Suite order:

```text
baseline -> load -> spike -> stress
```

Command mode streams k6 output directly to the console.

## Artifact Layout

Interactive runs write immutable folders under:

```text
docs/performance/<phase>/runs/<run-id>/
```

Example single-run layout:

```text
docs/performance/baseline/runs/20260504T153658-k6-baseline-940a85/
|-- k6_baseline_summary.json
|-- k6_baseline_context.json
|-- k6_baseline_input.json
|-- k6_baseline_report.json
|-- k6_baseline_report.md
`-- logs/
    `-- k6_baseline.log
```

Example suite-run layout:

```text
docs/performance/baseline/runs/20260504T170500-k6-suite-b91d2a/
|-- k6_baseline_*
|-- k6_load_*
|-- k6_spike_*
|-- k6_stress_*
|-- k6_suite_report.md
`-- logs/
    |-- k6_baseline.log
    |-- k6_load.log
    |-- k6_spike.log
    `-- k6_stress.log
```

Generated artifacts under `docs/performance/` are ignored by Git.

## Reports & History

The workbench can:

- browse recent runs
- select a run
- preview Markdown reports
- show full artifact/log paths
- find the latest suite report

## Environment Check

Environment Check shows:

- Node version
- platform
- current working directory
- config file status
- base URL
- output root
- resolved artifact root
- baseline/optimized run directory status
- k6 availability/version

## Settings

Settings is read-only for now.

It shows:

- config file path
- base URL
- output root
- resolved artifact root
- default phase
- CPU usage percentage
- memory usage percentage
- current directory

Edit `performance.config.json` manually to change settings.

## Deterministic Analysis

k6 summary output is normalized into the existing `PerformanceInput` shape and analyzed deterministically.

Reports include:

- latency summary
- error rate
- throughput
- observations
- bottleneck classification
- evidence
- suggestions
- notes

Bottleneck types:

- `CPU_BOUND`
- `IO_BOUND`
- `CONTENTION`
- `SCALABILITY`
- `UNKNOWN`

## AI Review Status

The current AI layer is an integration boundary, not live AI-assisted analysis.

It includes:

- AI review models
- prompt builder
- provider interface
- stub provider
- provider-selection mechanism

The current default is `StubAIReviewProvider`.

Use the current wording: AI review integration boundary with stub provider.

Do not claim live AI-assisted performance analysis until a real provider is implemented.

## Observability

### Config Location

```text
tools/performance/observability/
├── prometheus/
│   └── prometheus.yml              — Prometheus scrape config
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/prometheus.yml
│   │   └── dashboards/dashboard.yml
│   └── dashboards/
│       └── searchess-jvm.json      — JVM metrics dashboard
└── docker-compose.observability.yml
```

### Backend Metrics Endpoint

The game service exposes Prometheus-compatible metrics at:

```
GET http://localhost:8080/metrics
```

This endpoint is on the direct game-service port (8080), **not** routed through Envoy (port 10000).
Prometheus must scrape port 8080 directly.

Exposed metric families:

**JVM metrics** (always present):

| Metric | Type | Description |
|---|---|---|
| `jvm_memory_heap_used_bytes` | gauge | Heap memory currently in use |
| `jvm_memory_heap_committed_bytes` | gauge | Heap memory committed to OS |
| `jvm_memory_heap_max_bytes` | gauge | Maximum heap memory |
| `jvm_threads_current` | gauge | Live thread count |
| `process_uptime_seconds` | gauge | Process uptime in seconds |
| `jvm_gc_collection_count_total` | counter | GC collection count (per collector) |
| `jvm_gc_collection_seconds_total` | counter | GC pause time in seconds (per collector) |

**HTTP metrics** (populated after the first request):

| Metric | Type | Labels | Description |
|---|---|---|---|
| `http_requests_total` | counter | `method`, `route`, `status` | Total HTTP requests by method, normalized route, and status code |
| `http_request_duration_seconds` | histogram | `method`, `route`, `status` | Request duration in seconds with cumulative buckets |

Route labels use template patterns instead of raw dynamic values to prevent unbounded cardinality:
- `/sessions/{sessionId}`, `/games/{gameId}`, `/archive/games/{gameId}`, `/ops/history-outbox/{id}`

Histogram buckets (seconds): `0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, +Inf`

No application logic, user data, request/response bodies, or secrets are exposed.

### Starting Prometheus and Grafana

Prerequisite: Docker with Compose plugin.

```powershell
cd tools/performance/observability
docker compose -f docker-compose.observability.yml up -d
```

- Prometheus: http://localhost:9090
- Grafana:    http://localhost:3000  (credentials: admin / admin)

Prometheus scrapes `host.docker.internal:8080/metrics`. On Linux without Docker Desktop
the `extra_hosts: host.docker.internal:host-gateway` entry in the compose file maps that
hostname automatically.

There is currently **no start command that launches Prometheus/Grafana and the game service
together in a single step**. The observability stack and the main Docker Compose stack
(`docker-compose.yml`) are separate. For a fully containerised setup, target
`game-service:8080` in `prometheus.yml` and connect both compose projects to the same
Docker network.

### Grafana Dashboards

Two dashboards load automatically under the **Searchess** folder in Grafana.

**`searchess-jvm.json`** — JVM metrics:

- JVM heap memory (used / committed / max)
- Live thread count
- GC collection rate (per minute)
- GC pause time rate (per minute)
- Process uptime (stat panel)

**`searchess-http.json`** — HTTP request metrics:

- Request rate (req/s) by route and status
- Error rate (%) — percentage of 5xx responses by route
- p50 / p95 / p99 latency percentiles by route (histogram_quantile)
- Average latency by route

HTTP metrics only appear after the game service has received at least one request.

### Correlating with k6 Results

Observability metrics complement deterministic k6 reports — they explain **why** a
bottleneck was observed, not whether one exists:

1. Run a k6 load test from the workbench (generates a deterministic report).
2. While the test runs, watch the Grafana dashboards:
   - **searchess-http**: request rate and per-route p95/p99 latency rising under load confirms the k6 `http_req_duration` measurements from the backend's perspective.
   - **searchess-http**: error rate spiking indicates server-side failures independent of k6 thresholds.
   - **searchess-jvm**: heap climbing toward max → possible GC pressure or memory leak.
   - **searchess-jvm**: thread count spike → possible thread-pool saturation.
   - **searchess-jvm**: GC pause time rate increasing → GC is competing with request processing.
3. Correlate the timing: the Grafana time range covers the k6 test window.

Observability does **not** replace the deterministic reports. The deterministic analysis
(bottleneck type, confidence, evidence) remains the primary source of performance truth.
Grafana provides the backend-side explanation for what the deterministic report measured.

### Envoy Metrics (Not Yet Exposed)

Envoy's admin interface binds to `127.0.0.1:9901` inside the container and is not
reachable from an external Prometheus. To enable Envoy metrics, rebind the admin
address to `0.0.0.0:9901` in `config/envoy/envoy.yaml` and uncomment the Envoy job
in `prometheus.yml`. This is not done by default as it exposes the admin API.

## Future Extensions

- Gatling simulations
- JMH benchmarks
- real AI review provider
- CI artifact mode
- richer comparison workflows
