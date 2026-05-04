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

## Future Extensions

- Gatling simulations
- JMH benchmarks
- real AI review provider
- CI artifact mode
- richer comparison workflows
- optional Dockerized execution environment
