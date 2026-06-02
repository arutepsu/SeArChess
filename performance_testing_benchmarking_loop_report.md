# Searchess Performance Testing and Benchmarking Loop

## 1. Goal

This report documents the performance testing and benchmarking loop for Searchess. The goal was to establish a reproducible baseline, identify an optimization target, implement a safe fix, and rerun measurements to verify the effect.

The project uses three complementary performance layers:

| Tool | Purpose | Evidence type |
|---|---|---|
| k6 | End-to-end HTTP load testing | request latency, error rate, throughput, thresholds |
| Gatling | Code-first gameplay scenario testing | Scala scenario, feeders, semantic checks, assertions, native HTML report |
| JMH | Isolated JVM benchmarking | internal operation cost, score error, allocation per operation |

Prometheus, Grafana, and structured backend logs were used for observability and correlation, but the optimization evidence in this report comes primarily from JMH because the chosen fixes target internal JVM allocation and microsecond-level execution costs.

---

## 2. Reproducible Performance Setup

### k6

k6 is used for end-to-end HTTP testing through the Searchess API. It validates realistic service behavior under load and supports thresholds for p95 latency and error rate.

### Gatling

Gatling is used as a code-first Scala load-testing layer. The Searchess Gatling scenario includes:

- feeder-backed session mode input
- dynamic `sessionId` and `gameId` extraction
- semantic JSON checks
- grouped gameplay phases
- smoke, load, and stress workload profiles
- assertions for failed requests and p95 response time
- native Gatling HTML reports

### JMH

JMH is used for isolated JVM hot-path benchmarks. The benchmark suite includes:

- legal move generation
- move application
- in-memory game service calls
- DTO mapping
- JSON rendering
- optional GC/allocation profiling

JMH results are intentionally kept separate from the normalized k6/Gatling `PerformanceReport` model because JMH measures benchmark score, score error, units, and allocation rather than HTTP latency, throughput, or error rate.

---

## 3. Baseline System-Level Evidence

### Gatling baseline summary

A Gatling load run showed that the system was healthy under the configured baseline profile.

| Metric | Value |
|---|---:|
| Requests | 3900 total / 3900 OK / 0 KO |
| p95 latency | 48 ms |
| Error rate | 0.00% |
| Throughput | 61.90 req/s |
| Distribution | 100% below 800 ms |

The Gatling assertions passed:

- failed requests below 1%
- p95 response time below 500 ms

**Gatling bottleneck note:** Gatling did not reveal a system-level failure or HTTP bottleneck. The run stayed healthy with 0% errors and low p95 latency. This meant the optimization target should be found inside the JVM hot paths rather than by changing the external load profile.

### k6 baseline summary

k6 was used as the second end-to-end load-testing tool. It validates the same kind of HTTP-level behavior as Gatling, but with k6 thresholds and JavaScript test scripts.

| Metric | Result |
|---|---:|
| Checks | 100% passed |
| Error rate | 0.00% in healthy runs |
| Thresholds | Passed in the baseline run |

**k6 bottleneck note:** k6 confirmed that the API path was healthy at the tested workload. Like Gatling, it did not indicate a major HTTP-level error-rate or latency bottleneck. The optimization was therefore selected from the internal JMH evidence.

---

## 4. JMH Baseline and Hot Path Selection

The first JMH smoke run measured 16 internal JVM benchmarks.

**Run:** `20260505T195709-jmh-smoke-b2d253`  
**Phase:** `baseline`  
**Warmup:** 1 iteration  
**Measurement:** 1 iteration  
**Forks:** 1  
**GC profiler:** disabled

| Summary item | Value |
|---|---:|
| Benchmarks | 16 |
| Fastest | `LegalMoveGenerationBenchmark.legalMoves_checkPressurePosition` — 1.450 us/op |
| Slowest | `MappingBenchmark.mapSessionState` — 46.164 us/op |
| Allocation data | no |

The follow-up GC-profiled JMH run showed that `MappingBenchmark.mapSessionState` also had the highest allocation.

**Run:** `20260505T200227-jmh-gc-1151c1`  
**Warmup:** 3 iterations  
**Measurement:** 5 iterations  
**Forks:** 1  
**GC profiler:** enabled

| Summary item | Value |
|---|---:|
| Benchmarks | 16 |
| Slowest | `MappingBenchmark.mapSessionState` — 45.817 us/op |
| Highest allocation | `MappingBenchmark.mapSessionState` — 139,848.32 B/op |

This made response mapping the best optimization target. The load tests were healthy, while JMH showed that response construction and mapping were the clearest internal cost center.

---

## 5. Optimization 1: Precomputed Position Labels

### Problem

`Position.toString` generated algebraic square labels such as `e4` using string interpolation. Response mapping calls `Position.toString` repeatedly while building board DTOs, move history entries, and legal move maps.

There are only 64 valid chessboard positions, so repeatedly allocating equivalent strings is unnecessary.

### Fix

The optimization precomputed all 64 algebraic labels in the `Position` companion object and changed `Position.toString` to return the cached label.

```scala
object Position:
  val algebraicLabels: Array[Array[String]] =
    Array.tabulate(8, 8)((file, rank) => s"${('a' + file).toChar}${rank + 1}")

final case class Position private (file: Int, rank: Int):
  override def toString: String = Position.algebraicLabels(file)(rank)
```

This is a Flyweight-style optimization: the intrinsic immutable labels are reused instead of allocated repeatedly.

### Correctness verification

Tests were added for:

- corner squares: `a1`, `h1`, `a8`, `h8`
- center square: `e4`
- all 64 file/rank combinations
- all 64 round-trips through `fromAlgebraic`

No API shape, DTO, parsing, equality, or chess-rule behavior changed.

---

## 6. Optimization 1 Results

A short mapping-only JMH smoke run was used before and after the optimization.

### Before

**Run:** `20260505T215846-jmh-smoke-9f33f0`  
**Group:** Mapping  
**Warmup:** 1  
**Measurement:** 1  
**Forks:** 1  
**GC profiler:** disabled

| Benchmark | Before score |
|---|---:|
| `MappingBenchmark.mapGameState_initialPosition` | 6.052 us/op |
| `MappingBenchmark.mapGameState_midgamePosition` | 7.035 us/op |
| `MappingBenchmark.mapLegalMoves_initialPosition` | 5.057 us/op |
| `MappingBenchmark.mapSessionState` | 60.050 us/op |

### After

**Run:** `20260505T221924-jmh-smoke-12ff5c`  
**Group:** Mapping  
**Warmup:** 1  
**Measurement:** 1  
**Forks:** 1  
**GC profiler:** disabled

| Benchmark | After score | Change |
|---|---:|---:|
| `MappingBenchmark.mapGameState_initialPosition` | 4.664 us/op | -22.9% |
| `MappingBenchmark.mapGameState_midgamePosition` | 6.620 us/op | -5.9% |
| `MappingBenchmark.mapLegalMoves_initialPosition` | 4.362 us/op | -13.7% |
| `MappingBenchmark.mapSessionState` | 46.811 us/op | -22.0% |

These timing results are directional because the run used only one warmup and one measurement iteration. The stronger evidence came from allocation measurements.

### Allocation evidence

| Benchmark | Before allocation | After allocation | Change |
|---|---:|---:|---:|
| `mapGameState_initialPosition` | 15,328 B/op | 12,232 B/op | -3,096 B/op (-20%) |
| `mapGameState_midgamePosition` | 19,936 B/op | 15,404 B/op | -4,532 B/op (-23%) |
| `mapLegalMoves_initialPosition` | 14,432 B/op | 10,592 B/op | -3,840 B/op (-27%) |
| `mapSessionState` | 140,216 B/op | 135,193 B/op | -5,023 B/op (-3.6%) |

The allocation drop matched the expected savings from avoiding repeated short-lived algebraic square string creation.

---

## 7. Optimization 2: Allocation-Free Path Clearance

### Problem

A second investigation showed that `MoveValidator.isPathClear` used a `LazyList.iterate(...).takeWhile(...).forall(...)` pipeline to check intermediate squares for rook, bishop, queen, and sliding-attack validation.

This method is private but called frequently during legal move generation and king-safety checks. The lazy collection pipeline was expressive, but it created avoidable overhead in a hot path.

### Fix

The `LazyList` pipeline was replaced by an equivalent `while` loop over file/rank coordinates.

The loop:

- starts at the square immediately after `move.from`
- stops before `move.to`
- returns `false` on the first occupied intermediate square
- returns `true` if all intermediate squares are empty
- does not inspect the target square

No public API, chess rules, DTOs, or JSON response shapes changed.

### Correctness verification

Six domain tests were added:

- rook path blocked by a friendly piece
- rook adjacent capture with no intermediate squares
- queen open descending diagonal `h8 → a1`
- queen descending diagonal blocked by an intermediate piece
- rook pinned on the e-file, only legal along the file
- bishop pinned on the `a1–h8` diagonal, only legal along the diagonal

`domain/test` passed with 237 tests and 0 failures.

---

## 8. Optimization 2 Results

The second optimization was measured against the state after Optimization 1.

| Benchmark | Before score | After score | Before alloc | After alloc | Change |
|---|---:|---:|---:|---:|---:|
| `mapGameState_initialPosition` | 4.6 us/op | 4.5 us/op | 12,232 B/op | 12,052 B/op | -180 B/op |
| `mapGameState_midgamePosition` | 5.8 us/op | 6.0 us/op | 15,404 B/op | 15,212 B/op | -192 B/op |
| `mapLegalMoves_initialPosition` | 4.1 us/op | 4.1 us/op | 10,592 B/op | 10,592 B/op | 0 B/op |
| `mapSessionState` | 52.1 ± 13.9 us/op | 42.1 ± 2.1 us/op | 135,193 B/op | 131,272 B/op | -3,921 B/op |

The most important result is `mapSessionState`:

- mean time improved from 52.1 us/op to 42.1 us/op
- score error improved from ±13.9 us to ±2.1 us
- allocation dropped by 3,921 B/op

This suggests that replacing the lazy collection path with a direct loop reduced allocation pressure and made the benchmark more stable.

---

## 9. Cumulative JMH Result

Across both optimizations, the allocation profile improved consistently.

| Benchmark | Original allocation | After both optimizations | Total change |
|---|---:|---:|---:|
| `mapGameState_initialPosition` | 15,328 B/op | 12,052 B/op | -3,276 B/op (-21%) |
| `mapGameState_midgamePosition` | 19,936 B/op | 15,212 B/op | -4,724 B/op (-24%) |
| `mapLegalMoves_initialPosition` | 14,432 B/op | 10,592 B/op | -3,840 B/op (-27%) |
| `mapSessionState` | 140,216 B/op | 131,272 B/op | -8,944 B/op (-6%) |

The selected fixes did not change observable system behavior. They reduced internal allocation in response mapping and legal-move/check-path validation.

---

## 10. Interpretation

The load tests did not reveal an HTTP-level failure. Both k6 and Gatling showed healthy system-level behavior with low latency and zero errors in the selected runs. JMH then revealed that the best optimization target was not request routing or database access, but internal JVM allocation in response mapping and legal move validation.

The chosen fixes were therefore verified primarily with JMH:

1. **Precomputed Position labels** reduced repeated `Position.toString` allocation.
2. **Allocation-free path-clearance loop** reduced allocation and variance in a private move-validation hot path.

These optimizations are intentionally small and safe. They preserve API behavior, chess rules, DTO shape, JSON output, and deployment architecture.

---

## 11. Remaining Bottlenecks and Future Work

The largest remaining allocation source appears to be legal move generation itself, especially temporary board creation during candidate move validation and king-safety checks.

Possible future optimizations include:

- reducing `Move(from, to)` allocation inside attack checks
- avoiding repeated board-piece materialization where safe
- investigating board transition allocation in `Board.movePiece`
- considering king-position caching in `GameState`, if justified by stronger evidence

These are more invasive and should be treated as separate optimization loops with their own tests and JMH before/after evidence.

---

## 12. Conclusion

The performance loop was completed:

1. k6 and Gatling established reproducible system-level baselines.
2. JMH identified response mapping and legal-move validation as internal JVM hot paths.
3. Two safe optimizations were implemented.
4. Correctness tests passed.
5. JMH reruns showed reduced allocation and improved stability.

The final result is a measured, evidence-backed optimization rather than an unverified micro-optimization.

