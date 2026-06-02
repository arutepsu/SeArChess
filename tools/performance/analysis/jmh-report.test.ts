import assert from 'node:assert/strict';
import test from 'node:test';
import { buildJmhStructuredReport, parseJmhJson, renderJmhMarkdown, renderJmhStructuredMarkdown } from './application/jmhReport';
import { buildJmhSbtCommand, parseRunJmhArgs } from './cli/run-jmh';
import { buildJmhArtifactPaths, jmhProfileOptions } from './application/runJmhReport';
import {
  DEFAULT_JMH_GROUP_ID,
  findJmhBenchmarkGroup,
  listJmhBenchmarkGroups,
  resolveJmhPattern,
} from './application/jmhBenchmarkGroups';

const fixture = JSON.stringify([
  {
    benchmark: 'chess.benchmarks.LegalMoveGenerationBenchmark.legalMoves_initialPosition',
    mode: 'avgt',
    threads: 1,
    forks: 1,
    params: {},
    primaryMetric: {
      score: 27.0419,
      scoreError: 1.25,
      scoreUnit: 'us/op',
    },
    secondaryMetrics: {
      'gc.alloc.rate.norm': {
        score: 2048,
        scoreUnit: 'B/op',
      },
    },
  },
  {
    benchmark: 'chess.benchmarks.ParamBenchmark.case',
    mode: 'thrpt',
    params: { size: 'small' },
    primaryMetric: {
      score: 99,
      scoreUnit: 'ops/s',
    },
  },
]);

test('parseJmhJson reads benchmark score, unit, mode, and optional error', () => {
  const summary = parseJmhJson(fixture);

  assert.equal(summary.benchmarks.length, 2);
  assert.equal(summary.benchmarks[0].benchmark, 'chess.benchmarks.LegalMoveGenerationBenchmark.legalMoves_initialPosition');
  assert.equal(summary.benchmarks[0].mode, 'avgt');
  assert.equal(summary.benchmarks[0].score, 27.0419);
  assert.equal(summary.benchmarks[0].scoreError, 1.25);
  assert.equal(summary.benchmarks[0].unit, 'us/op');
  assert.equal(summary.benchmarks[0].allocationBytesPerOp, 2048);
});

test('parseJmhJson handles missing optional scoreError and allocation safely', () => {
  const summary = parseJmhJson(fixture);

  assert.equal(summary.benchmarks[1].scoreError, undefined);
  assert.equal(summary.benchmarks[1].allocationBytesPerOp, undefined);
  assert.deepEqual(summary.benchmarks[1].params, { size: 'small' });
});

test('renderJmhMarkdown includes benchmark name, score, unit, allocation, and notes', () => {
  const markdown = renderJmhMarkdown(parseJmhJson(fixture), { runId: 'local-jmh' });

  assert.match(markdown, /Searchess JMH Benchmark Report/);
  assert.match(markdown, /legalMoves_initialPosition/);
  assert.match(markdown, /27\.042/);
  assert.match(markdown, /us\/op/);
  assert.match(markdown, /Alloc B\/op/);
  assert.match(markdown, /2048\.00/);
  assert.match(markdown, /ops\/s \| - \| size=small/);
  assert.match(markdown, /Score error is JMH measurement uncertainty/);
});

test('parseRunJmhArgs supports short sanity options', () => {
  const parsed = parseRunJmhArgs(['--run-id', 'demo', '-wi', '1', '-i', '3', '-f', '1', '-t', '1']);

  assert.equal(parsed.runId, 'demo');
  assert.equal(parsed.warmupIterations, 1);
  assert.equal(parsed.measurementIterations, 3);
  assert.equal(parsed.forks, 1);
  assert.equal(parsed.threads, 1);
});

test('buildJmhSbtCommand writes JSON output and preserves benchmark pattern', () => {
  const options = parseRunJmhArgs(['--run-id', 'demo', '--pattern', 'chess.benchmarks.*']);
  const command = buildJmhSbtCommand(options, 'docs/performance/jmh/runs/demo/jmh-results.json');

  assert.match(command, /benchmarks \/ Jmh \/ run/);
  assert.match(command, /-rf json/);
  assert.match(command, /jmh-results\.json/);
  assert.match(command, /chess\.benchmarks\.\*/);
});

test('buildJmhSbtCommand appends gc profiler when requested', () => {
  const options = parseRunJmhArgs(['--run-id', 'demo', '--gc-profiler']);
  const command = buildJmhSbtCommand(options, 'docs/performance/jmh/runs/demo/jmh-results.json');

  assert.match(command, /-prof gc/);
});

test('buildJmhStructuredReport counts benchmarks and identifies fastest and slowest', () => {
  const report = buildJmhStructuredReport({
    summary: parseJmhJson(fixture),
    runId: 'run-123',
    phase: 'baseline',
    createdAt: '2026-05-05T12:00:00.000Z',
    options: jmhProfileOptions('smoke'),
  });

  assert.equal(report.tool, 'jmh');
  assert.equal(report.benchmarkCount, 2);
  assert.equal(report.summary.fastestBenchmark?.shortName, 'LegalMoveGenerationBenchmark.legalMoves_initialPosition');
  assert.equal(report.summary.slowestBenchmark?.shortName, 'ParamBenchmark.case');
});

test('buildJmhStructuredReport captures allocation availability and highest allocation benchmark', () => {
  const report = buildJmhStructuredReport({
    summary: parseJmhJson(fixture),
    runId: 'run-123',
    phase: 'baseline',
    options: jmhProfileOptions('gc'),
  });

  assert.equal(report.summary.allocationDataAvailable, true);
  assert.equal(report.summary.highestAllocationBenchmark?.allocationBytesPerOp, 2048);
});

test('renderJmhStructuredMarkdown includes structured summary and options', () => {
  const report = buildJmhStructuredReport({
    summary: parseJmhJson(fixture),
    runId: 'run-123',
    phase: 'optimized',
    options: jmhProfileOptions('gc'),
  });
  const markdown = renderJmhStructuredMarkdown(report, {
    rawJsonPath: 'jmh_results.json',
    rawTextPath: 'jmh_results.txt',
    structuredJsonPath: 'jmh_report.json',
  });

  assert.match(markdown, /Run ID: `run-123`/);
  assert.match(markdown, /Phase: `optimized`/);
  assert.match(markdown, /Allocation data: yes/);
  assert.match(markdown, /GC profiler \| enabled/);
  assert.match(markdown, /jmh_report\.json/);
});

test('buildJmhArtifactPaths uses workbench underscore artifact names', () => {
  const paths = buildJmhArtifactPaths('baseline', 'docs/performance/baseline/runs/demo');

  assert.match(paths.rawJsonPath, /jmh_results\.json$/);
  assert.match(paths.rawTextPath, /jmh_results\.txt$/);
  assert.match(paths.reportJsonPath, /jmh_report\.json$/);
  assert.match(paths.markdownPath, /jmh_report\.md$/);
});

// ---------------------------------------------------------------------------
// Benchmark group model
// ---------------------------------------------------------------------------

test('listJmhBenchmarkGroups returns all 11 expected groups', () => {
  const groups = listJmhBenchmarkGroups();
  assert.equal(groups.length, 11);

  const ids = groups.map((g) => g.id);
  assert.ok(ids.includes('all'));
  assert.ok(ids.includes('domain-rules'));
  assert.ok(ids.includes('move-application'));
  assert.ok(ids.includes('game-service'));
  assert.ok(ids.includes('mapping'));
  assert.ok(ids.includes('json-rendering'));
  assert.ok(ids.includes('response-construction'));
  assert.ok(ids.includes('postgres-persistence'));
  assert.ok(ids.includes('mongo-persistence'));
  assert.ok(ids.includes('persistence'));
  assert.ok(ids.includes('custom'));
});

test('resolveJmhPattern returns correct pattern for each named group', () => {
  assert.equal(resolveJmhPattern('all'), 'chess.benchmarks.*');
  assert.equal(resolveJmhPattern('domain-rules'), 'chess.benchmarks.LegalMoveGenerationBenchmark.*');
  assert.equal(resolveJmhPattern('move-application'), 'chess.benchmarks.MoveApplicationBenchmark.*');
  assert.equal(resolveJmhPattern('game-service'), 'chess.benchmarks.GameServiceBenchmark.*');
  assert.equal(resolveJmhPattern('mapping'), 'chess.benchmarks.MappingBenchmark.*');
  assert.equal(resolveJmhPattern('json-rendering'), 'chess.benchmarks.JsonRenderingBenchmark.*');
  assert.equal(resolveJmhPattern('response-construction'), 'chess.benchmarks.*(MappingBenchmark|JsonRenderingBenchmark).*');
  assert.equal(resolveJmhPattern('postgres-persistence'), 'chess.benchmarks.persistence.Postgres.*RepositoryBenchmark.*');
  assert.equal(resolveJmhPattern('mongo-persistence'), 'chess.benchmarks.persistence.mongo.Mongo.*RepositoryBenchmark.*');
  assert.equal(resolveJmhPattern('persistence'), 'chess.benchmarks.persistence.*');
});

test('resolveJmhPattern persistence group avoids shell pipe characters', () => {
  assert.doesNotMatch(resolveJmhPattern('persistence'), /\|/);
});

test('DEFAULT_JMH_GROUP_ID is all', () => {
  assert.equal(DEFAULT_JMH_GROUP_ID, 'all');
});

test('resolveJmhPattern custom group requires a pattern', () => {
  assert.throws(() => resolveJmhPattern('custom'), /requires a pattern/);
  assert.throws(() => resolveJmhPattern('custom', ''), /requires a pattern/);
});

test('resolveJmhPattern custom group returns the provided pattern', () => {
  assert.equal(resolveJmhPattern('custom', 'chess.benchmarks.MyBenchmark.*'), 'chess.benchmarks.MyBenchmark.*');
});

test('findJmhBenchmarkGroup returns the correct group by id', () => {
  const group = findJmhBenchmarkGroup('response-construction');
  assert.equal(group.label, 'Response construction');
  assert.equal(group.pattern, 'chess.benchmarks.*(MappingBenchmark|JsonRenderingBenchmark).*');
});

// ---------------------------------------------------------------------------
// --group flag in CLI
// ---------------------------------------------------------------------------

test('parseRunJmhArgs --group response-construction resolves to response-construction pattern', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test', '--group', 'response-construction']);
  assert.equal(opts.pattern, 'chess.benchmarks.*(MappingBenchmark|JsonRenderingBenchmark).*');
  assert.equal(opts.benchmarkGroupId, 'response-construction');
  assert.equal(opts.benchmarkGroupLabel, 'Response construction');
});

test('parseRunJmhArgs --group builds correct sbt command', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test', '--group', 'response-construction']);
  const cmd = buildJmhSbtCommand(opts, 'out/jmh-results.json');
  assert.match(cmd, /MappingBenchmark\|JsonRenderingBenchmark/);
});

test('parseRunJmhArgs --group postgres-persistence resolves to Postgres persistence pattern', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test', '--group', 'postgres-persistence']);
  assert.equal(opts.pattern, 'chess.benchmarks.persistence.Postgres.*RepositoryBenchmark.*');
  assert.equal(opts.benchmarkGroupId, 'postgres-persistence');
  assert.equal(opts.benchmarkGroupLabel, 'Postgres persistence');
});

test('parseRunJmhArgs --group mongo-persistence resolves to Mongo persistence pattern', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test', '--group', 'mongo-persistence']);
  assert.equal(opts.pattern, 'chess.benchmarks.persistence.mongo.Mongo.*RepositoryBenchmark.*');
  assert.equal(opts.benchmarkGroupId, 'mongo-persistence');
  assert.equal(opts.benchmarkGroupLabel, 'Mongo persistence');
});

test('parseRunJmhArgs --group persistence resolves to combined persistence pattern', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test', '--group', 'persistence']);
  assert.equal(opts.pattern, 'chess.benchmarks.persistence.*');
  assert.equal(opts.benchmarkGroupId, 'persistence');
  assert.equal(opts.benchmarkGroupLabel, 'Persistence all');
});

test('parseRunJmhArgs --pattern still works without --group', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test', '--pattern', 'chess.benchmarks.*']);
  assert.equal(opts.pattern, 'chess.benchmarks.*');
  assert.equal(opts.benchmarkGroupId, undefined);
});

test('parseRunJmhArgs --group and --pattern together fail with clear error', () => {
  assert.throws(
    () => parseRunJmhArgs(['--run-id', 'test', '--group', 'all', '--pattern', 'chess.benchmarks.*']),
    /Use either --group or --pattern, not both/,
  );
});

test('parseRunJmhArgs defaults to group all when neither --group nor --pattern supplied', () => {
  const opts = parseRunJmhArgs(['--run-id', 'test']);
  assert.equal(opts.benchmarkGroupId, 'all');
  assert.equal(opts.pattern, 'chess.benchmarks.*');
});

test('parseRunJmhArgs rejects invalid --group id', () => {
  assert.throws(
    () => parseRunJmhArgs(['--group', 'nonexistent']),
    /Invalid --group/,
  );
});

test('parseRunJmhArgs rejects --group custom (not valid in command mode)', () => {
  assert.throws(
    () => parseRunJmhArgs(['--group', 'custom']),
    /Invalid --group/,
  );
});

// ---------------------------------------------------------------------------
// Structured report with group metadata
// ---------------------------------------------------------------------------

test('buildJmhStructuredReport includes benchmarkGroupId and benchmarkGroupLabel in options', () => {
  const report = buildJmhStructuredReport({
    summary: parseJmhJson(fixture),
    runId: 'run-grp',
    phase: 'baseline',
    createdAt: '2026-05-05T12:00:00.000Z',
    options: {
      ...jmhProfileOptions('gc'),
      benchmarkGroupId: 'response-construction',
      benchmarkGroupLabel: 'Response construction',
    },
  });

  assert.equal(report.options.benchmarkGroupId, 'response-construction');
  assert.equal(report.options.benchmarkGroupLabel, 'Response construction');
  assert.equal(report.options.pattern, 'chess.benchmarks.*');
});

test('renderJmhStructuredMarkdown includes Benchmark group line when label is present', () => {
  const report = buildJmhStructuredReport({
    summary: parseJmhJson(fixture),
    runId: 'run-grp',
    phase: 'baseline',
    options: {
      ...jmhProfileOptions('baseline'),
      benchmarkGroupId: 'response-construction',
      benchmarkGroupLabel: 'Response construction',
    },
  });
  const md = renderJmhStructuredMarkdown(report);

  assert.match(md, /Benchmark group: `Response construction`/);
  assert.match(md, /Benchmark group \| Response construction/);
});

test('renderJmhStructuredMarkdown omits Benchmark group lines when no label', () => {
  const report = buildJmhStructuredReport({
    summary: parseJmhJson(fixture),
    runId: 'run-nogrp',
    phase: 'baseline',
    options: jmhProfileOptions('smoke'),
  });
  const md = renderJmhStructuredMarkdown(report);

  assert.doesNotMatch(md, /Benchmark group/);
});

test('renderJmhMarkdown includes Benchmark group line when label is present', () => {
  const summary = parseJmhJson(fixture);
  const md = renderJmhMarkdown(summary, {
    runId: 'run-123',
    benchmarkGroupLabel: 'Domain rules',
  });

  assert.match(md, /Benchmark group: `Domain rules`/);
});
