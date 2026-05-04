# Performance Validation Report

This report documents the reproducible performance test setup for the Searchess backend. Current
k6 and Gatling tests target the Game Service API, not the Vite/frontend dev server.

## Target

Default backend target:

```bash
http://localhost:8080
```

Override it with `BASE_URL`:

```bash
BASE_URL=http://localhost:8080 k6 run tools/performance/k6/baseline_test.js
```

PowerShell:

```powershell
$env:BASE_URL = "http://localhost:8080"; k6 run tools/performance/k6/baseline_test.js
```

## Workload

The k6 baseline/load/stress/spike workloads use stable backend gameplay endpoints:

- `POST /sessions` with `{"mode":"HumanVsHuman"}`
- `GET /games/{gameId}/legal-moves`
- `POST /games/{gameId}/moves`
- `GET /sessions/{sessionId}/state`

Each virtual user lifecycle creates a fresh isolated session, then runs a sequential gameplay loop:
fetch legal moves, submit a deterministic legal move, and fetch the updated state. Core k6 gameplay
steps deliberately avoid `http.batch`; the goal is to model player interaction order and exercise
both read and write paths, not to maximize synthetic traffic. Move selection is deterministic:
legal moves are sorted by `from/to/promotion` and selected from the current ply number, keeping runs
comparable without external feeders or uncontrolled randomness.

## k6 Tests

| Test | File | Measures | Thresholds |
| :--- | :--- | :--- | :--- |
| Baseline | `tools/performance/k6/baseline_test.js` | Repeatable low-load player lifecycle with 4 plies | p95 `< 500 ms`, error rate `< 1%` |
| Load | `tools/performance/k6/load_test.js` | Sustained normal load using the same lifecycle at 50 VUs | p95 `< 500 ms`, error rate `< 1%` |
| Stress | `tools/performance/k6/stress_test.js` | Higher pressure ramp/hold behavior using the same lifecycle | p95 `< 1000 ms`, error rate `< 5%` |
| Spike | `tools/performance/k6/spike_test.js` | Sudden jump from 20 to 150 VUs using the same lifecycle | p95 `< 1000 ms`, error rate `< 5%` |

Run and export summaries:

```bash
BASE_URL=http://localhost:8080 k6 run tools/performance/k6/baseline_test.js --summary-export docs/performance/baseline/k6_baseline_summary.json
BASE_URL=http://localhost:8080 k6 run tools/performance/k6/load_test.js --summary-export docs/performance/baseline/k6_load_summary.json
BASE_URL=http://localhost:8080 k6 run tools/performance/k6/stress_test.js --summary-export docs/performance/baseline/k6_stress_summary.json
BASE_URL=http://localhost:8080 k6 run tools/performance/k6/spike_test.js --summary-export docs/performance/baseline/k6_spike_summary.json
```

PowerShell example:

```powershell
$env:BASE_URL = "http://localhost:8080"
k6 run tools/performance/k6/baseline_test.js --summary-export docs/performance/baseline/k6_baseline_summary.json
k6 run tools/performance/k6/load_test.js --summary-export docs/performance/baseline/k6_load_summary.json
k6 run tools/performance/k6/stress_test.js --summary-export docs/performance/baseline/k6_stress_summary.json
k6 run tools/performance/k6/spike_test.js --summary-export docs/performance/baseline/k6_spike_summary.json
```

Summaries are stored in `docs/performance/baseline/`.

## Gatling

`modules/load-tests/src/test/scala/gatling/GameSimulation.scala` targets backend session and game
read endpoints. It reads the target from, in order:

- JVM system property `BASE_URL`
- JVM system property `baseUrl`
- environment variable `BASE_URL`
- default `http://localhost:8080`

Run:

```bash
BASE_URL=http://localhost:8080 sbt "loadTests/Gatling/testOnly gatling.GameSimulation"
```

PowerShell:

```powershell
$env:BASE_URL = "http://localhost:8080"; sbt "loadTests/Gatling/testOnly gatling.GameSimulation"
```

System property form:

```bash
sbt -DBASE_URL=http://localhost:8080 "loadTests/Gatling/testOnly gatling.GameSimulation"
```

Assertions:

- p95 response time `< 500 ms`
- failed requests `< 1%`

## JMH

`modules/benchmarks/src/main/scala/chess/rules/LegalMoveGeneratorBenchmark.scala` benchmarks
`LegalMoveGenerator.legalMovesFrom` and `LegalMoveGenerator.legalTargetsFrom` for real initial
position pieces. The benchmark has warmup iterations, measurement iterations, one fork, throughput
mode, and returns the generated sets so JMH cannot eliminate the work.

Run:

```bash
sbt "benchmarks/Jmh/run -wi 3 -i 5 -f1 -t1 chess.rules.LegalMoveGeneratorBenchmark"
```

With allocation profiling:

```bash
sbt "benchmarks/Jmh/run -wi 3 -i 5 -f1 -t1 -prof gc chess.rules.LegalMoveGeneratorBenchmark"
```

## Current Baseline Status

Earlier k6/Gatling numbers in this project were collected against frontend/dev-server URLs such as
the Vite `/game` route. Treat those numbers as preliminary and not comparable to backend API
results. New baseline files should be generated with the commands above and committed under
`docs/performance/baseline/`.

## Next Step

Run the backend baseline suite, identify one bottleneck from the k6/Gatling/JMH output, optimize
that bottleneck, then rerun the same commands and store the optimized summaries beside the baseline
results for comparison.
