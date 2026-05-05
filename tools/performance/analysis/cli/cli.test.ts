import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ANALYZE = join(__dirname, '..', 'cli', 'analyze.js');
const COMPARE  = join(__dirname, '..', 'cli', 'compare.js');

function writeTmp(dir: string, name: string, content: unknown): string {
  const filePath = join(dir, name);
  writeFileSync(filePath, JSON.stringify(content));
  return filePath;
}

const validInput = {
  metadata:     { test_type: 'load', scenario_name: 'default', timestamp: '2026-05-04T00:00:00Z' },
  load_profile: { max_users: 50, duration: '5m', ramp_up_pattern: 'linear' },
  latency:      { p50: 150, p95: 600, p99: 900 },
  throughput:   { requests_per_second: 400 },
  errors:       { error_rate: 0.01, total_errors: 40 },
  system:       { cpu_usage_percent: 65, memory_usage_percent: 50 },
};

const validReport = {
  metadata:     { test_type: 'load', scenario_name: 'test', timestamp: '2026-05-04T00:00:00Z' },
  summary:      { p95_latency: 600, error_rate: 0.01, throughput: 400 },
  observations: ['p95 latency is 600ms'],
  bottleneck:   { type: 'UNKNOWN', confidence: 'LOW' },
  evidence:     ['no strong signal'],
  suggestions:  ['gather more data'],
  notes:        [],
};

// ---------------------------------------------------------------------------
// analyze command
// ---------------------------------------------------------------------------

test('analyze: exits 0 and prints JSON report for valid input', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const inPath = writeTmp(dir, 'input.json', validInput);
  const result = spawnSync('node', [ANALYZE, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const report = JSON.parse(result.stdout);
  assert.ok(typeof report.bottleneck === 'object');
  assert.ok(typeof report.summary    === 'object');
});

test('analyze: exits 1 and writes to stderr for invalid JSON', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const bad    = join(dir, 'bad.json');
  writeFileSync(bad, 'not valid json');
  const result = spawnSync('node', [ANALYZE, bad], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.length > 0);
});

test('analyze: exits 1 and writes to stderr for invalid PerformanceInput', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const inPath = writeTmp(dir, 'invalid.json', { wrong: true });
  const result = spawnSync('node', [ANALYZE, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Invalid PerformanceInput'));
});

test('analyze: exits 1 and prints usage when no argument given', () => {
  const result = spawnSync('node', [ANALYZE], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Usage'));
});

test('analyze: exits 1 when file does not exist', () => {
  const result = spawnSync('node', [ANALYZE, '/nonexistent/path/input.json'], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.length > 0);
});

// ---------------------------------------------------------------------------
// compare command
// ---------------------------------------------------------------------------

test('compare: exits 0 and prints comparison JSON for two valid reports', () => {
  const dir      = mkdtempSync(join(tmpdir(), 'perf-'));
  const basePath = writeTmp(dir, 'base.json',      validReport);
  const optPath  = writeTmp(dir, 'optimized.json', { ...validReport, summary: { p95_latency: 400, error_rate: 0.005, throughput: 500 } });
  const result   = spawnSync('node', [COMPARE, basePath, optPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const report = JSON.parse(result.stdout);
  assert.ok(typeof report.verdict       === 'string');
  assert.ok(typeof report.improvement   === 'object');
  assert.ok(Array.isArray(report.interpretation));
});

test('compare: exits 1 and prints usage when arguments are missing', () => {
  const result = spawnSync('node', [COMPARE], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Usage'));
});

test('compare: exits 1 when a report file does not exist', () => {
  const dir      = mkdtempSync(join(tmpdir(), 'perf-'));
  const basePath = writeTmp(dir, 'base.json', validReport);
  const result   = spawnSync('node', [COMPARE, basePath, '/nonexistent/optimized.json'], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.length > 0);
});
