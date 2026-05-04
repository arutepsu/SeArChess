import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runPerformancePipeline } from './application/runPerformancePipeline';

const PIPELINE = join(__dirname, 'cli', 'pipeline.js');

const baselineInput = {
  metadata:     { test_type: 'load', scenario_name: 'api-baseline', timestamp: '2026-05-04T00:00:00Z' },
  load_profile: { max_users: 100, duration: '5m', ramp_up_pattern: 'linear' },
  latency:      { p50: 250, p95: 800, p99: 1200 },
  throughput:   { requests_per_second: 350 },
  errors:       { error_rate: 0.03, total_errors: 90 },
  system:       { cpu_usage_percent: 85, memory_usage_percent: 60 },
};

const optimizedInput = {
  metadata:     { test_type: 'load', scenario_name: 'api-optimized', timestamp: '2026-05-04T01:00:00Z' },
  load_profile: { max_users: 100, duration: '5m', ramp_up_pattern: 'linear' },
  latency:      { p50: 160, p95: 420, p99: 700 },
  throughput:   { requests_per_second: 500 },
  errors:       { error_rate: 0.01, total_errors: 30 },
  system:       { cpu_usage_percent: 65, memory_usage_percent: 55 },
};

function writeTmp(dir: string, name: string, content: unknown): string {
  const filePath = join(dir, name);
  writeFileSync(filePath, typeof content === 'string' ? content : JSON.stringify(content));
  return filePath;
}

test('runPerformancePipeline returns complete Markdown report', async () => {
  const markdown = await runPerformancePipeline({ baselineInput, optimizedInput });
  assert.ok(markdown.includes('# Performance Review'));
  assert.ok(markdown.includes('## Performance Report'));
  assert.ok(markdown.includes('## Comparison Report'));
  assert.ok(markdown.includes('## AI Review'));
});

test('runPerformancePipeline uses optimized report in final Markdown', async () => {
  const markdown = await runPerformancePipeline({ baselineInput, optimizedInput });
  assert.ok(markdown.includes('- p95 latency: 420ms'));
  assert.ok(markdown.includes('- Scenario: api-optimized'));
});

test('cli pipeline: exits 0 and prints Markdown', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-pipeline-'));
  const baselinePath = writeTmp(dir, 'baseline.json', baselineInput);
  const optimizedPath = writeTmp(dir, 'optimized.json', optimizedInput);
  const result = spawnSync('node', [PIPELINE, baselinePath, optimizedPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  assert.ok(result.stdout.includes('# Performance Review'));
  assert.ok(result.stdout.includes('## Performance Report'));
  assert.ok(result.stdout.includes('## Comparison Report'));
  assert.ok(result.stdout.includes('## AI Review'));
});

test('cli pipeline: supports custom title', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-pipeline-'));
  const baselinePath = writeTmp(dir, 'baseline.json', baselineInput);
  const optimizedPath = writeTmp(dir, 'optimized.json', optimizedInput);
  const result = spawnSync('node', [PIPELINE, baselinePath, optimizedPath, 'Nightly Performance Review'], {
    encoding: 'utf-8',
  });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  assert.ok(result.stdout.includes('# Nightly Performance Review'));
});

test('cli pipeline: exits 1 when required args are missing', () => {
  const result = spawnSync('node', [PIPELINE], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Usage'));
});

test('cli pipeline: exits 1 on invalid JSON', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-pipeline-'));
  const baselinePath = writeTmp(dir, 'baseline.json', 'not json');
  const optimizedPath = writeTmp(dir, 'optimized.json', optimizedInput);
  const result = spawnSync('node', [PIPELINE, baselinePath, optimizedPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Error reading'));
});

test('cli pipeline: exits 1 when one input is invalid PerformanceInput', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-pipeline-'));
  const baselinePath = writeTmp(dir, 'baseline.json', baselineInput);
  const optimizedPath = writeTmp(dir, 'optimized.json', { wrong: true });
  const result = spawnSync('node', [PIPELINE, baselinePath, optimizedPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Invalid PerformanceInput'));
});
