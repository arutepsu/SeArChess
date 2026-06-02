import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  artifactSummaryFromJmhPaths,
  renderArtifactSummary,
  renderJmhRunResult,
  renderJmhToolSummary,
  renderObservabilityHint,
  renderRunArtifactPaths,
  renderRunMetadata,
} from './workbenchView';
import type { JmhStructuredReport } from '../../application/jmhReport';
import type { RunHistoryItem } from '../reports/runHistory';

function sampleJmhReport(allocation = true): JmhStructuredReport {
  return {
    tool: 'jmh',
    runId: 'jmh-run',
    phase: 'baseline',
    createdAt: '2026-05-05T12:00:00.000Z',
    benchmarkCount: 2,
    options: {
      warmupIterations: 1,
      measurementIterations: 1,
      forks: 1,
      threads: 1,
      gcProfiler: allocation,
      pattern: 'chess.benchmarks.*',
    },
    results: [
      {
        benchmark: 'chess.benchmarks.LegalMoveGenerationBenchmark.legalMoves_initialPosition',
        shortName: 'LegalMoveGenerationBenchmark.legalMoves_initialPosition',
        mode: 'avgt',
        score: 10,
        unit: 'us/op',
        params: {},
        allocationBytesPerOp: allocation ? 2048 : undefined,
      },
      {
        benchmark: 'chess.benchmarks.MoveApplicationBenchmark.applyMove_openingMove',
        shortName: 'MoveApplicationBenchmark.applyMove_openingMove',
        mode: 'avgt',
        score: 20,
        unit: 'us/op',
        params: {},
      },
    ],
    summary: {
      fastestBenchmark: {
        benchmark: 'chess.benchmarks.LegalMoveGenerationBenchmark.legalMoves_initialPosition',
        shortName: 'LegalMoveGenerationBenchmark.legalMoves_initialPosition',
        score: 10,
        unit: 'us/op',
      },
      slowestBenchmark: {
        benchmark: 'chess.benchmarks.MoveApplicationBenchmark.applyMove_openingMove',
        shortName: 'MoveApplicationBenchmark.applyMove_openingMove',
        score: 20,
        unit: 'us/op',
      },
      allocationDataAvailable: allocation,
      highestAllocationBenchmark: allocation ? {
        benchmark: 'chess.benchmarks.LegalMoveGenerationBenchmark.legalMoves_initialPosition',
        shortName: 'LegalMoveGenerationBenchmark.legalMoves_initialPosition',
        allocationBytesPerOp: 2048,
      } : undefined,
    },
    notes: [],
  };
}

test('renderObservabilityHint: includes runId in output', () => {
  const result = renderObservabilityHint('run-123', 'http://localhost:10000/api');
  assert.ok(result.includes('run-123'), `expected runId in output, got:\n${result}`);
});

test('renderObservabilityHint: includes all four performance header names', () => {
  const result = renderObservabilityHint('run-123', 'http://localhost:10000/api');
  assert.ok(result.includes('X-Performance-Run-Id'),    'missing X-Performance-Run-Id');
  assert.ok(result.includes('X-Performance-Tool'),      'missing X-Performance-Tool');
  assert.ok(result.includes('X-Performance-Workload'),  'missing X-Performance-Workload');
  assert.ok(result.includes('X-Performance-Phase'),     'missing X-Performance-Phase');
});

test('renderObservabilityHint: includes Observability section header', () => {
  const result = renderObservabilityHint('run-abc', 'http://localhost:10000/api');
  assert.ok(result.includes('Observability'));
});

test('renderObservabilityHint: includes log search hint with runId', () => {
  const result = renderObservabilityHint('run-abc', 'http://localhost:10000/api');
  assert.ok(result.includes('performanceRunId=run-abc'), `expected performanceRunId=run-abc in output, got:\n${result}`);
});

test('renderObservabilityHint: includes baseUrl as target', () => {
  const result = renderObservabilityHint('run-xyz', 'http://myserver:8080/api');
  assert.ok(result.includes('http://myserver:8080/api'), `expected baseUrl in output, got:\n${result}`);
});

test('renderObservabilityHint: includes /metrics reference', () => {
  const result = renderObservabilityHint('run-xyz', 'http://localhost:10000/api');
  assert.ok(result.includes('/metrics'), `expected /metrics reference in output, got:\n${result}`);
});

test('renderJmhRunResult shows benchmark summary without PerformanceReport fields', () => {
  const output = renderJmhRunResult(sampleJmhReport());

  assert.match(output, /Benchmarks/);
  assert.match(output, /Fastest/);
  assert.match(output, /Slowest/);
  assert.match(output, /Allocation data/);
  assert.doesNotMatch(output, /Bottleneck/);
});

test('renderJmhToolSummary shows JMH execution options', () => {
  const output = renderJmhToolSummary(sampleJmhReport());

  assert.match(output, /Warmup iterations/);
  assert.match(output, /Measurement iterations/);
  assert.match(output, /Forks/);
  assert.match(output, /Threads/);
  assert.match(output, /GC profiler/);
  assert.match(output, /chess\.benchmarks\.\*/);
});

test('artifactSummaryFromJmhPaths renders report, raw JSON, and output paths', () => {
  const summary = artifactSummaryFromJmhPaths({
    outDir: 'docs/performance/baseline/runs/jmh-run',
    rawJsonPath: 'docs/performance/baseline/runs/jmh-run/jmh_results.json',
    rawTextPath: 'docs/performance/baseline/runs/jmh-run/jmh_results.txt',
    reportJsonPath: 'docs/performance/baseline/runs/jmh-run/jmh_report.json',
    markdownPath: 'docs/performance/baseline/runs/jmh-run/jmh_report.md',
  });
  const output = renderArtifactSummary(summary);

  assert.match(output, /Report/);
  assert.match(output, /Report JSON/);
  assert.match(output, /JMH JSON/);
  assert.match(output, /JMH output/);
});

test('renderRunArtifactPaths includes JMH JSON and output artifacts', () => {
  const runDir = mkdtempSync(join(tmpdir(), 'jmh-history-'));
  writeFileSync(join(runDir, 'jmh_report.md'), '# JMH');
  writeFileSync(join(runDir, 'jmh_report.json'), '{}');
  writeFileSync(join(runDir, 'jmh_results.json'), '[]');
  writeFileSync(join(runDir, 'jmh_results.txt'), 'output');
  const item: RunHistoryItem = {
    runId: 'jmh-run',
    phase: 'baseline',
    path: runDir,
    kind: 'jmh-single',
    reports: [join(runDir, 'jmh_report.md')],
    htmlReports: [],
    logs: [join(runDir, 'jmh_results.txt')],
  };

  const output = renderRunArtifactPaths(item);

  assert.match(output, /jmh_report\.json/);
  assert.match(output, /jmh_results\.json/);
  assert.match(output, /jmh_results\.txt/);
});

// ---------------------------------------------------------------------------
// renderRunMetadata — group field
// ---------------------------------------------------------------------------

test('renderRunMetadata includes Group when group is provided', () => {
  const output = renderRunMetadata({
    tool: 'jmh',
    workload: 'gc',
    group: 'Response construction',
    phase: 'baseline',
    runId: 'run-001',
  });

  assert.match(output, /Group/);
  assert.match(output, /Response construction/);
});

test('renderRunMetadata omits Group line when group is not provided', () => {
  const output = renderRunMetadata({
    tool: 'k6',
    workload: 'load',
    phase: 'baseline',
    runId: 'run-002',
  });

  assert.doesNotMatch(output, /Group/);
});

test('renderRunMetadata includes Pattern when pattern is provided', () => {
  const output = renderRunMetadata({
    tool: 'gatling',
    workload: 'smoke',
    pattern: 'Gameplay flow',
    phase: 'baseline',
    runId: 'run-002b',
  });

  assert.match(output, /Pattern/);
  assert.match(output, /Gameplay flow/);
});

test('renderRunMetadata always shows Tool, Workload, Phase, and Run ID', () => {
  const output = renderRunMetadata({
    tool: 'gatling',
    workload: 'smoke',
    phase: 'optimized',
    runId: 'run-003',
  });

  assert.match(output, /gatling/);
  assert.match(output, /smoke/);
  assert.match(output, /optimized/);
  assert.match(output, /run-003/);
});

// ---------------------------------------------------------------------------
// renderJmhToolSummary — benchmark group and pattern ordering
// ---------------------------------------------------------------------------

function sampleJmhReportWithGroup(): JmhStructuredReport {
  return {
    ...sampleJmhReport(),
    options: {
      ...sampleJmhReport().options,
      benchmarkGroupId: 'response-construction',
      benchmarkGroupLabel: 'Response construction',
    },
  };
}

test('renderJmhToolSummary shows Benchmark group when present', () => {
  const output = renderJmhToolSummary(sampleJmhReportWithGroup());
  assert.match(output, /Benchmark group/);
  assert.match(output, /Response construction/);
});

test('renderJmhToolSummary shows Pattern', () => {
  const output = renderJmhToolSummary(sampleJmhReport());
  assert.match(output, /Pattern/);
});

test('renderJmhToolSummary omits Benchmark group row when no group label set', () => {
  const output = renderJmhToolSummary(sampleJmhReport());
  assert.doesNotMatch(output, /Benchmark group/);
});

test('renderJmhToolSummary shows Pattern before Warmup iterations', () => {
  const output = renderJmhToolSummary(sampleJmhReportWithGroup());
  const patternIdx = output.indexOf('Pattern');
  const warmupIdx = output.indexOf('Warmup');
  assert.ok(patternIdx < warmupIdx, 'Pattern should appear before Warmup iterations');
});

test('renderJmhToolSummary shows Benchmark group before Pattern', () => {
  const output = renderJmhToolSummary(sampleJmhReportWithGroup());
  const groupIdx = output.indexOf('Benchmark group');
  const patternIdx = output.indexOf('Pattern');
  assert.ok(groupIdx < patternIdx, 'Benchmark group should appear before Pattern');
});
