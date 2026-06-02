# Searchess Performance Workbench

## Overview

The Searchess Performance Workbench is an in-repo performance tool for running, analyzing, and browsing performance-test evidence for the Searchess backend.

It currently supports k6 load tests, Gatling simulations, and a first JMH benchmark suite for isolated JVM hot paths.

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

JMH is integrated as a separate benchmark lane. It writes structured JMH artifacts and history entries, but it is intentionally not converted into the k6/Gatling `PerformanceReport` model.

Interactive k6 runs:

- use quiet/log output
- show spinner and progress feedback
- write raw k6 output to log files
- create immutable run folders
- can be browsed later via Reports & History

Interactive single-test runs print three separate sections:

- `Result`: the normalized deterministic Searchess analysis used for bottleneck classification.
- `Tool Summary`: native k6 or Gatling metrics read from the tool's summary artifact.
- `Artifacts`: generated report, log, and native report paths.

In the `Result` section, `Status` describes the health of the run, `Bottleneck` describes the detected limiting factor, and `Diagnosis confidence` describes confidence in that bottleneck diagnosis. A healthy run with no latency, error-rate, or resource pressure may correctly show no detected bottleneck with low diagnosis confidence; that means the analyzer has little evidence for a specific limiting factor, not that the run itself is unreliable.

Keep these meanings separate. Native k6/Gatling metrics are useful evidence for understanding the run, but they are not the normalized analyzer result and do not change the deterministic bottleneck rules.

Interactive JMH runs use JMH-specific sections instead:

- `Result`: benchmark count, fastest/slowest score, and allocation availability.
- `Tool Summary`: JMH options such as warmup iterations, measurement iterations, forks, threads, GC profiler, and benchmark pattern.
- `Artifacts`: structured JMH report, raw JMH JSON, captured JMH console output, and Markdown report.

JMH does not emit observability correlation headers because it does not send HTTP requests.

The interactive workbench uses semantic colorized terminal output for readability: section headers are highlighted, healthy/pass/OK values are green, warnings and low/medium diagnosis confidence are yellow, failures/errors/KO values are red, unknown values are muted, and artifact paths are displayed in muted blue. These colors are only presentation hints in the terminal. They do not change saved Markdown reports, JSON reports, normalized inputs, or analyzer behavior.

Very long paths may be shortened with a middle `...` in terminal output so filenames remain visible. This is display-only; the actual artifact paths and saved files still use the full paths.

In the `Run` section, `Workload` means the selected test profile, for example `load`. `Phase` means the system version being measured, for example `baseline` before optimization and `optimized` after optimization.

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

## Gatling Simulations

Gatling runs via the SBT `gatlingPerf` subproject using the `io.gatling:gatling-sbt` plugin.

### Why Gatling?

Gatling is included because it demonstrates a different performance-testing style than k6:

- **Code-first Scala DSL**: simulations are source code, reviewed and version-controlled with the project.
- **Scenario composition**: user journeys can be built from reusable `ChainBuilder` steps instead of one long script.
- **Reporting and visualization**: Gatling Open Source generates its own HTML report for each simulation run.
- **Mature automation fit**: simulations run from SBT, so they can be launched from scripts and normalized by the Searchess workbench.

### Open Source vs Enterprise

Searchess uses **Gatling Open Source only**.

Gatling Open Source is free, Apache 2.0 licensed, and provides the core load-testing engine, Scala DSL, checks, feeders, injection profiles, and local HTML reports.

Gatling Enterprise is commercial. It adds hosted or self-hosted dashboards, collaboration, advanced analytics, alerting, and managed enterprise workflows. This repository does not configure Gatling Cloud or Gatling Enterprise.

### Code-First Philosophy

The Searchess simulation lives at:

```text
tools/performance/gatling/src/test/scala/searchess/simulations/SearchessGameplaySimulation.scala
```

Because the test is Scala code, it can use IDE navigation, compiler checks, refactoring, comments, constants, functions, and reusable chain builders. The current simulation is intentionally small, but it still shows the minimal Gatling structure:

- `Simulation` class
- `httpProtocol`
- `scenario`
- request checks and JSON extraction
- feeder-backed test data
- explicit injection profile with `rampUsers` and `constantUsersPerSec`

### Prerequisite

`sbt` must be on the PATH. The Gatling simulation compiles and runs via `sbt "project gatlingPerf" "Gatling/test"`.

### Run from CLI

```powershell
cd C:\Users\cgmar\IdeaProjects\searchess\tools\performance\analysis
npm run perf -- gatling
```

With explicit settings:

```powershell
npm run perf -- gatling --test smoke --gatling-pattern gameplay --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline
npm run perf -- gatling --test load --gatling-pattern legalMoves --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline
npm run perf -- gatling --test stress --gatling-pattern writeHeavy --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline
npm run perf -- start
```

All options may come from `performance.config.json`; CLI arguments override.

For observability correlation, prefer the direct local backend URL:

```text
http://localhost:8080
```

That is the same service port that exposes `GET /metrics`, so Gatling traffic lines up cleanly with Prometheus and Grafana HTTP route metrics. The Envoy edge path can still be used when the goal is to test the public edge:

```text
http://localhost:10000/api
```

Supported options:

- `--test`       Gatling workload profile: `smoke`, `load`, or `stress` (default: `load`)
- `--gatling-pattern` Scenario pattern: `all`, `gameplay`, `session`, `legalMoves`, `moveSubmission`, `readHeavy`, or `writeHeavy` (default: `gameplay`)
- `--base-url`   Target base URL
- `--cpu`        CPU usage % (0–100)
- `--memory`     Memory usage % (0–100)
- `--phase`      `baseline` or `optimized`
- `--out`        Output directory override

### Run from Interactive Workbench

Select **Gatling Simulations** -> **Run load Gatling simulation** from the workbench menu, then choose the Gatling scenario pattern.

Interactive Gatling runs:

- write raw sbt/Gatling output to a log file
- show a spinner with elapsed time
- produce the same deterministic report format as k6 runs
- appear in Reports & History as `gatling-single` runs

### Gatling Workloads and Scenario Patterns

The Gatling integration keeps two concepts separate:

- `searchess.gatling.workload`: injection profile, one of `smoke`, `load`, or `stress`. Default: `load`.
- `searchess.gatling.pattern`: scenario/workflow pattern, one of `all`, `gameplay`, `session`, `legalMoves`, `moveSubmission`, `readHeavy`, or `writeHeavy`. Default: `gameplay`.

The workbench passes both values through to sbt as JVM system properties.

Direct sbt examples:

```bash
sbt \
  -Dsearchess.gatling.workload=smoke \
  -Dsearchess.gatling.pattern=gameplay \
  "gatlingPerf / Gatling / testOnly searchess.simulations.SearchessGameplaySimulation"

sbt \
  -Dsearchess.gatling.workload=load \
  -Dsearchess.gatling.pattern=legalMoves \
  "gatlingPerf / Gatling / testOnly searchess.simulations.SearchessGameplaySimulation"

sbt \
  -Dsearchess.gatling.workload=stress \
  -Dsearchess.gatling.pattern=writeHeavy \
  "gatlingPerf / Gatling / testOnly searchess.simulations.SearchessGameplaySimulation"
```

Workload profiles:

- `smoke`: `rampUsers(3).during(3.seconds)` for a very small, fast validation run.
- `load`: `rampUsers(50).during(10.seconds)` plus `constantUsersPerSec(5).during(50.seconds)`. This is the normal benchmark profile and preserves the original Gatling behavior.
- `stress`: `rampUsers(100).during(15.seconds)` plus `constantUsersPerSec(10).during(60.seconds)` for bottleneck exploration.

Scenario patterns:

- `all`: run the full Gatling suite available in the current simulation class.
- `gameplay`: create session, fetch legal moves, submit moves, and fetch updated state.
- `session`: only session creation.
- `legalMoves`: create session, then repeatedly fetch legal moves.
- `moveSubmission`: create session, fetch legal moves, and submit moves.
- `readHeavy`: mostly read operations such as legal moves and state.
- `writeHeavy`: mostly move submission operations.

Use `load` for normal before/after comparisons. Use `smoke` to confirm the scenario, feeder, groups, semantic JSON checks, and assertions still work. Use `stress` to explore pressure points; it may fail Gatling quality gates if the service crosses the configured error-rate or p95 latency limits, and its results should not be compared directly to `load`.

Gatling and k6 are only directly comparable when their workload profiles are equivalent. Native Gatling/k6 metrics are useful for understanding each run, but the normalized Searchess `Result` remains the deterministic analyzer view.

### Scenario Composition

The simulation composes the gameplay journey from reusable chain builders:

- `createSession`
- `fetchLegalMoves`
- `submitMove`
- `fetchUpdatedState`
- `gameplayTurn`
- `gameplayLoop`

The composed flow mirrors the k6 gameplay scenario:

1. `POST /sessions` — creates an independent session per virtual user
2. Loop 4 plies:
   - `GET /games/{gameId}/legal-moves`
   - `POST /games/{gameId}/moves` — deterministic move selection (sorted by from-to)
   - `GET /sessions/{sessionId}/state`
3. 200ms think time between steps

Load profile: ramp to 50 users over 10 s, then constant 5 users/s for 50 s.

### Gatling Quality Gates and Checks

The simulation uses Gatling assertions as local quality gates:

- failed requests must stay below 1%
- global p95 response time must stay below 500 ms

These assertions are evaluated by Gatling at the end of the run. If the service crosses either boundary, the Gatling task fails even before the Searchess analyzer reads the normalized report.

The scenario also uses Gatling groups around the major gameplay phases:

- `Create session`
- `Fetch legal moves`
- `Submit move`
- `Fetch updated state`
- `Gameplay turn`

Those groups make the native Gatling HTML report easier to inspect because request timings are organized by gameplay intent instead of appearing as one flat request list.

The request checks include semantic JSON checks, not only HTTP status checks. The simulation verifies dynamic session/game IDs, session mode, legal move data, submitted-move state, and updated session state using stable API fields. It avoids brittle assertions against exact board layouts or move histories beyond the minimal contract needed for the happy-path gameplay loop.

These native Gatling checks complement the workbench output. The normalized Searchess `Result` remains the deterministic analyzer view, while Gatling assertions, groups, and native metrics remain tool-specific evidence in the Gatling report.

### Feeder Pattern

The simulation includes a minimal CSV feeder:

```text
tools/performance/gatling/src/test/resources/searchess/session_modes.csv
```

It feeds the existing `mode` field for `POST /sessions`, currently `HumanVsHuman`. The important runtime IDs are still extracted dynamically from API responses:

- `sessionId`
- `gameId`

This keeps the API contract honest while demonstrating the feeder pattern. In larger Searchess scenarios, feeders would be useful for authenticated users, imported positions, predefined openings, or user-specific test data.

### Reporting Pipeline

Gatling Open Source generates its native HTML report under the Gatling results directory. Searchess also reads Gatling's `global_stats.json`, normalizes it into the shared `PerformanceInput`, runs the deterministic analyzer, and writes the same report shape used by the rest of the workbench.

Searchess report artifacts have distinct purposes:

- JSON report: machine-readable deterministic `PerformanceReport`
- Markdown report: text/report documentation for terminals and review
- Report HTML: browser-friendly Searchess report rendered from normalized deterministic analysis
- Gatling HTML: native Gatling Open Source report with Gatling-specific charts, groups, and assertions

Do not confuse Report HTML with Gatling HTML. Report HTML belongs to the Searchess workbench. Gatling HTML is generated by Gatling itself and remains separate.

### Gatling Artifact Layout

```text
docs/performance/baseline/runs/20260505T120000-gatling-load-abcdef/
|-- gatling_load_summary.json      — Gatling global_stats.json (wrapped)
|-- gatling_load_context.json      — normalizer context
|-- gatling_load_input.json        — normalized PerformanceInput
|-- gatling_load_report.json       — deterministic PerformanceReport
|-- gatling_load_report.md         — Markdown report
|-- gatling_load_report.html       — Searchess browser-friendly HTML report
`-- logs/
    `-- gatling_load.log
```

### Environment Check

Environment Check now reports whether the Gatling simulation Scala source file is found:

```text
Gatling:         configured [ok]
```

or:

```text
Gatling:         not configured [warn]
```

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
|-- k6_baseline_report.html
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
|-- k6_suite_report.html
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
- show full Markdown, HTML, and log artifact paths
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

**Searchess domain metrics** (always present, zero-valued until the first observation):

| Metric | Type | Description |
|---|---|---|
| `searchess_sessions_created_total` | counter | Successful `POST /sessions` responses (201 only) |
| `searchess_games_created_total` | counter | Successful chess games created (always equal to sessions_created_total) |
| `searchess_legal_moves_requested_total` | counter | Legal-move generation requests with a valid game ID |
| `searchess_moves_submitted_total` | counter | Move submissions that reached the game service (after body and move parsing) |
| `searchess_legal_move_generation_duration_seconds` | histogram | Time from service call to result for `GET /games/{gameId}/legal-moves` |
| `searchess_submit_move_duration_seconds` | histogram | Time from service call to result for `POST /games/{gameId}/moves` |
| `searchess_fetch_state_duration_seconds` | histogram | Time from service call to result for `GET /sessions/{sessionId}/state` |

Domain metrics use no session IDs, game IDs, player IDs, or run IDs as labels. Run IDs belong
in structured logs and workbench artifacts, not in Prometheus time series.

Example `/metrics` lines after a short smoke test:

```text
searchess_sessions_created_total 3
searchess_games_created_total 3
searchess_legal_moves_requested_total 12
searchess_moves_submitted_total 12
searchess_legal_move_generation_duration_seconds_bucket{le="0.005"} 9
searchess_legal_move_generation_duration_seconds_bucket{le="0.01"} 12
searchess_legal_move_generation_duration_seconds_bucket{le="+Inf"} 12
searchess_legal_move_generation_duration_seconds_sum 0.082
searchess_legal_move_generation_duration_seconds_count 12
searchess_submit_move_duration_seconds_bucket{le="0.025"} 10
searchess_submit_move_duration_seconds_bucket{le="+Inf"} 12
searchess_submit_move_duration_seconds_count 12
```

No application logic, user data, request/response bodies, or secrets are exposed.

### Starting Prometheus and Grafana

Prerequisite: Docker with Compose plugin.

For Docker-only performance testing, start the main Searchess stack first so Docker
creates the external `searchess_default` network:

```powershell
docker compose up -d --build
```

Then start observability:

```powershell
cd tools/performance/observability
docker compose -f docker-compose.observability.yml up -d
```

- Prometheus: http://localhost:9090
- Grafana:    http://localhost:3000  (credentials: admin / admin)

Default Docker-only mode:

- workbench target: `http://localhost:10000/api`
- Prometheus scrape target: `game-service:8080/metrics`
- networking: the Prometheus container joins both the observability network and the
  external `searchess_default` app network

Grafana only needs to reach Prometheus, so it stays on the observability network.

Local SBT/backend mode is different. If the game service is running on the host at
`http://localhost:8080`, Prometheus can scrape `host.docker.internal:8080` instead.
Use a local-specific Prometheus config or temporarily switch the target in
`prometheus.yml`. Do not confuse local host backend testing with Docker-only testing:
`game-service:8080` is the Docker container target, while `host.docker.internal:8080`
is the host backend target.

### Grafana Dashboards

Three dashboards load automatically under the **Searchess** folder in Grafana.

Use them together during k6 and Gatling runs:

- **Searchess — HTTP Metrics**: API-level request rate, error rate, and route latency.
- **Searchess JVM**: runtime heap, threads, garbage collection, and process uptime.
- **Searchess — Domain Metrics**: chess/game operation counters and operation-level latency.

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

**`searchess-domain.json`** — chess/game domain metrics:

- Domain operation rate for sessions, games, legal move requests, and submitted moves
- Total sessions created
- Total games created
- Total legal move requests
- Total submitted moves
- p95 legal move generation latency
- p95 submit move latency
- p95 fetch state latency
- p95 domain latency comparison across legal moves, submit move, and fetch state

HTTP metrics only appear after the game service has received at least one request. Domain metrics
(`searchess_*`) appear from startup (always zero-valued until the first observation).

**Recommended panels during a load test:**

| Panel | Query sketch | Why useful |
|---|---|---|
| Request rate | `rate(http_requests_total[1m])` | Overall server load |
| p95 HTTP latency | `histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[1m]))` | Tail latency across all routes |
| p99 HTTP latency | `histogram_quantile(0.99, ...)` | Worst-case user experience |
| Error rate | `rate(http_requests_total{status=~"5.."}[1m])` | Server-side failures |
| Legal move p95 | `histogram_quantile(0.95, rate(searchess_legal_move_generation_duration_seconds_bucket[1m]))` | Chess rule engine under load |
| Submit move p95 | `histogram_quantile(0.95, rate(searchess_submit_move_duration_seconds_bucket[1m]))` | Full move path latency |
| Fetch state p95 | `histogram_quantile(0.95, rate(searchess_fetch_state_duration_seconds_bucket[1m]))` | Session persistence read latency |
| Sessions created | `rate(searchess_sessions_created_total[1m])` | Throughput of session creation |
| Moves submitted | `rate(searchess_moves_submitted_total[1m])` | Move throughput |
| JVM heap | `jvm_memory_heap_used_bytes / jvm_memory_heap_max_bytes` | Memory pressure |
| GC pause rate | `rate(jvm_gc_collection_seconds_total[1m])` | GC competing with requests |

### Performance Run Correlation

k6 and Gatling requests include local correlation headers so a run can be followed from
the workbench output to generated artifacts, backend logs, Prometheus/Grafana timing,
and optional traces if tracing is enabled later:

| Header | Meaning | Local fallback |
|---|---|---|
| `X-Performance-Run-Id` | Workbench run folder id when available | `local-dev` |
| `X-Performance-Tool` | Load tool that generated the request | `k6` or `gatling` |
| `X-Performance-Workload` | Selected workload/test profile | selected profile, or `smoke` in standalone k6 scripts |
| `X-Performance-Phase` | System phase under test | `baseline`, `optimized`, or `local` |

These headers are intentionally request metadata, not Prometheus labels. Backend HTTP
metrics continue to use bounded labels such as method, route template, and status so
run ids, session ids, and game ids cannot create high-cardinality time series.

The direct local backend URL remains preferred for observability correlation:

```powershell
npm run perf -- gatling --test load --gatling-pattern gameplay --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline
```

The Envoy path can still be used when the goal is to test the public edge:

```powershell
npm run perf -- gatling --test load --gatling-pattern gameplay --base-url http://localhost:10000/api --cpu 72 --memory 61 --phase baseline
```

### Correlating with k6 and Gatling Results

Observability metrics complement deterministic k6 and Gatling reports — they explain **why** a
bottleneck was observed, not whether one exists:

1. Run a k6 or Gatling load test from the workbench (generates a deterministic report).
2. While the test runs, watch the Grafana dashboards:
   - **searchess-http**: request rate and per-route p95/p99 latency rising under load confirms k6 or Gatling measurements from the backend's perspective.
   - **searchess-http**: error rate spiking indicates server-side failures independent of k6 thresholds or Gatling assertions.
   - **searchess-jvm**: heap climbing toward max → possible GC pressure or memory leak.
   - **searchess-jvm**: thread count spike → possible thread-pool saturation.
   - **searchess-jvm**: GC pause time rate increasing → GC is competing with request processing.
   - **searchess-domain**: sessions, games, legal moves, submitted moves, and domain p95 latency show where chess/game work is accumulating.
3. Correlate the timing: the Grafana time range covers the performance test window.

Observability does **not** replace the deterministic reports. The deterministic analysis
(bottleneck type, confidence, evidence) remains the primary source of performance truth.
Grafana provides the backend-side explanation for what the deterministic report measured.
Domain metrics complement the normalized Searchess `Result` and native k6/Gatling
reports; run ids remain in logs and artifacts, not Prometheus labels.

### Correlating with Backend Request Logs

Every HTTP request handled by the game service emits a structured JSON-lines log entry at the
`request_completed` event. The entry includes the four performance correlation headers injected by
both k6 and Gatling, making it possible to filter the log for exactly one test run.

Example log line (formatted for readability):

```json
{
  "ts": "2026-05-05T14:03:22.417Z",
  "level": "info",
  "service": "game-service",
  "event": "request_completed",
  "method": "POST",
  "route": "/sessions",
  "status": 201,
  "durationMs": 4.7,
  "performanceRunId": "baseline-2026-05-05",
  "performanceTool": "gatling",
  "performanceWorkload": "load",
  "performancePhase": "baseline"
}
```

#### Correlation workflow

1. **Copy the run ID** — the workbench sets `GATLING_RUN_ID` / `PERFORMANCE_RUN_ID` to the output
   directory name (e.g. `baseline`). Override it with `--out <dir>` to use a descriptive token such
   as `baseline-2026-05-05`.
2. **Run the test** — while the test is active, the game service streams one log line per request to
   stdout.
3. **Filter by run ID** — after the run, search the captured log:

   ```powershell
   # PowerShell — filter captured log by run ID
   Select-String -Path game-service.log -Pattern '"performanceRunId":"baseline-2026-05-05"'
   ```

   ```bash
   # bash / jq — pretty-print matching entries
   grep '"performanceRunId":"baseline-2026-05-05"' game-service.log | jq .
   ```

4. **Open the Grafana time window** — the first and last `ts` values from the filtered lines define
   the exact test window. Use those timestamps to set the Grafana dashboard time range, then overlay
   the request-rate and latency panels with the workbench report numbers.

#### What is and is not in the log

| Field | Logged |
|---|---|
| HTTP method | yes |
| Normalized route template | yes (e.g. `/games/{gameId}/moves`) |
| Response status code | yes |
| Request duration (ms) | yes |
| Performance run ID / tool / workload / phase | yes |
| Request or response body | **no** |
| Session ID or Game ID (as log fields) | **no** |
| User identity or credentials | **no** |

Session IDs and game IDs appear only in the normalized route template (`{sessionId}`, `{gameId}`),
never as discrete high-cardinality log fields.

### Envoy Metrics (Not Yet Exposed)

Envoy's admin interface binds to `127.0.0.1:9901` inside the container and is not
reachable from an external Prometheus. To enable Envoy metrics, rebind the admin
address to `0.0.0.0:9901` in `config/envoy/envoy.yaml` and uncomment the Envoy job
in `prometheus.yml`. This is not done by default as it exposes the admin API.

## Verify Observability Locally

After a run completes, the workbench prints an **Observability** block showing the run ID, the
performance headers, and where to look for metrics and logs. Use the hints there to verify the
end-to-end chain:

### 1. Run a quick Gatling smoke test

```powershell
cd tools/performance/analysis
npm run perf -- start     # select Gatling → smoke, note the Run ID shown in the Observability block
```

### 2. Check domain metrics incremented

While the backend is still up, scrape the ops metrics endpoint:

```bash
curl -s http://localhost:8080/metrics | grep searchess_
```

Expected output after a smoke run (values will vary):

```
# HELP searchess_sessions_created_total Total number of sessions created
# TYPE searchess_sessions_created_total counter
searchess_sessions_created_total 10
# HELP searchess_games_created_total Total number of games created
# TYPE searchess_games_created_total counter
searchess_games_created_total 10
# HELP searchess_legal_moves_requested_total Total number of legal move requests
# TYPE searchess_legal_moves_requested_total counter
searchess_legal_moves_requested_total 47
# HELP searchess_moves_submitted_total Total number of moves submitted
# TYPE searchess_moves_submitted_total counter
searchess_moves_submitted_total 47
```

Each Gatling user creates a session and plays moves, so `sessions_created` and `games_created`
should match the user count, and `legal_moves_requested` / `moves_submitted` should be higher
(one legal-moves request per move played).

### 3. Filter backend logs by run ID

The game service logs one JSON-lines entry per request. The `performanceRunId` field matches the
run ID shown in the workbench Observability block.

```bash
# bash — filter by run ID
grep '"performanceRunId":"<runId>"' game-service.log | jq .

# PowerShell
Select-String -Path game-service.log -Pattern '"performanceRunId":"<runId>"'
```

A healthy smoke run produces entries like:

```json
{"ts":"2026-05-05T14:00:01.234Z","level":"INFO","service":"game-service","event":"request_completed",
 "method":"POST","route":"/sessions","status":201,"durationMs":4.7,
 "performanceRunId":"baseline-2026-05-05","performanceTool":"gatling",
 "performanceWorkload":"smoke","performancePhase":"baseline"}
```

### 4. What to check

| Signal | Where | Pass criterion |
|---|---|---|
| `searchess_sessions_created_total` | `GET /metrics` | Matches Gatling user count |
| `searchess_legal_moves_requested_total` | `GET /metrics` | Matches total legal-move calls in Gatling log |
| `request_completed` log lines | `game-service.log` | One per request; no `request_failed` events |
| `performanceRunId` in logs | `game-service.log` | Matches run ID in workbench Observability block |

> **Note:** The run ID appears in the request logs and in artifact paths. It is intentionally
> absent from Prometheus labels to avoid high-cardinality label explosion. Use Grafana for
> aggregated metrics and the log file for per-run filtering.

## JMH Benchmarks

k6 and Gatling measure end-to-end HTTP/system behavior: client load, routing, request parsing,
application work, metrics, serialization, and response handling. JMH measures isolated JVM hot
paths inside Searchess so local rule-engine and application-service costs can be inspected without
HTTP, Docker, Prometheus, Grafana, or network noise.

JMH is useful for Scala/JVM code because it manages warmup, JIT compilation effects, dead-code
elimination, forks, timing modes, and measurement stability. Its results should not be compared
directly with k6 or Gatling throughput. Use JMH to understand internal operation cost; use k6 and
Gatling to understand full-service behavior under load.

The initial benchmark suite lives in `modules/benchmarks` and targets:

- legal move generation through `GameStateRules.legalMoves`
- move application/state transition through `GameStateRules.applyMove`
- the in-memory `DefaultGameService` path for `getLegalMoves` and `submitMove`
- domain/application-to-DTO mapping through `GameView.fromState`, `GameMapper`, and `SessionMapper`
- JSON response rendering through `GameResponse.toJson`, `LegalMovesResponse.toJson`,
  `SessionStateResponse.toJson`, and `ujson.write`

The mapping and JSON benchmarks isolate response construction cost without executing HTTP routes.
They are useful when k6 or Gatling shows response latency and you need to separate chess/domain
cost from DTO construction or JSON rendering cost.

The benchmark project is intentionally not aggregated into normal test runs. Run it explicitly:

```powershell
# Short sanity run
sbt "benchmarks / Jmh / run -wi 1 -i 3 -f1 -t1 chess.benchmarks.*"

# Full local run
sbt "benchmarks / Jmh / run -wi 5 -i 10 -f2 -t1 chess.benchmarks.*"

# Optional JSON output for later analysis
sbt "benchmarks / Jmh / run -wi 3 -i 5 -f1 -t1 -rf json -rff docs/performance/jmh/jmh-results.json chess.benchmarks.*"
```

### JMH Artifact Workflow

From the interactive workbench, choose `JMH Benchmarks` and one of:

- `Run smoke JMH benchmark`: `-wi 1 -i 1 -f1 -t1`, useful for validating benchmark wiring.
- `Run baseline JMH benchmark`: `-wi 3 -i 5 -f1 -t1`, the normal local evidence profile.
- `Run GC/allocation JMH benchmark`: baseline options plus `-prof gc`, useful for allocation bytes/op.

Interactive JMH runs use the same phase/run-folder discipline as k6 and Gatling:

```text
docs/performance/<phase>/runs/<run-id>/
|-- jmh_results.json   # raw JMH JSON output
|-- jmh_results.txt    # captured SBT/JMH console output
|-- jmh_report.json    # structured Searchess JMH report model
`-- jmh_report.md      # Searchess JMH Markdown summary
```

Reports & History detects these folders as `jmh-single` runs and previews `jmh_report.md`.

Use the lightweight report command when you want to preserve benchmark evidence:

```powershell
cd tools/performance/analysis
npm run build
npm run jmh:report -- --run-id baseline-rules-20260505
```

The command creates a run folder under:

```text
docs/performance/jmh/runs/<run-id>/
|-- jmh-results.json   # raw JMH JSON output
|-- jmh-results.txt    # captured SBT/JMH console output
`-- jmh-report.md      # lightweight Searchess Markdown summary
```

The default command runs:

```powershell
sbt "benchmarks / Jmh / run -wi 3 -i 5 -f1 -t1 -rf json -rff docs/performance/jmh/runs/<run-id>/jmh-results.json chess.benchmarks.*"
```

For a quick artifact smoke run, lower the JMH iterations:

```powershell
npm run jmh:report -- --run-id smoke-jmh --warmup-iterations 1 --measurement-iterations 1 --forks 1 --threads 1
```

To include JMH GC/allocation secondary metrics, add the optional GC profiler:

```powershell
npm run jmh:report -- --run-id baseline-jmh-gc --gc-profiler
```

### Benchmark Groups

Use `--group <id>` to run a named subset of the benchmark suite instead of typing a raw JMH pattern. The workbench interactive flow presents the same list as a selection prompt.

| Group ID               | Label                  | JMH Pattern                                               | When to use                                              |
|------------------------|------------------------|-----------------------------------------------------------|----------------------------------------------------------|
| `all`                  | All benchmarks         | `chess.benchmarks.*`                                      | Full suite baseline; default when no flag is supplied    |
| `domain-rules`         | Domain rules           | `chess.benchmarks.LegalMoveGenerationBenchmark.*`         | Isolate legal-move generation cost                       |
| `move-application`     | Move application       | `chess.benchmarks.MoveApplicationBenchmark.*`             | Isolate state-transition and move-application cost       |
| `game-service`         | Game service           | `chess.benchmarks.GameServiceBenchmark.*`                 | In-memory application-service boundary cost              |
| `mapping`              | Mapping                | `chess.benchmarks.MappingBenchmark.*`                     | Domain/application model to DTO mapping cost             |
| `json-rendering`       | JSON rendering         | `chess.benchmarks.JsonRenderingBenchmark.*`               | DTO to JSON rendering/serialization cost                 |
| `response-construction`| Response construction  | `chess.benchmarks.*(MappingBenchmark\|JsonRenderingBenchmark).*` | Mapping plus JSON rendering together               |

`--group` and `--pattern` are mutually exclusive. Use `--pattern <regex>` for any pattern not covered by the named groups.

```powershell
# Run only the response-construction group with GC profiler
npm run jmh:report -- --run-id rc-baseline --group response-construction --gc-profiler

# Run only domain-rules as a quick smoke check
npm run jmh:report -- --run-id smoke-rules --group domain-rules -wi 1 -i 1 -f 1
```

The generated Markdown table includes benchmark name, JMH mode, score, score error when present,
unit, allocation bytes/op when present, and benchmark parameters. For `AverageTime` (`avgt`),
lower scores are faster for the same benchmark and unit. Score error is JMH measurement
uncertainty, not an application error rate. Allocation values are optional and appear only when
JMH emits secondary metrics such as `gc.alloc.rate.norm` from `-prof gc`.

Keep JMH reports separate from the normalized k6/Gatling reports. JMH reports help explain isolated
JVM operation cost; they do not replace end-to-end k6/Gatling evidence and are not forced into the
Searchess `PerformanceReport` or `PerformanceInput` shapes.

The current suite uses deterministic in-memory fixtures only. `submitMove` is reset per benchmark
invocation so repeated measurements do not mutate one game into an invalid turn state.

## Future Extensions

- real AI review provider
- CI artifact mode
- richer comparison workflows
