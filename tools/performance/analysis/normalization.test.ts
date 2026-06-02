import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from  'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { normalizeGatlingSummary } from './normalization/gatlingSummaryNormalizer';
import { normalizeK6Summary } from './normalization/k6SummaryNormalizer';
import type { NormalizerContext } from './normalization/normalizerModels';

const NORMALIZE_K6 = join(__dirname, 'cli', 'normalize-k6.js');
const NORMALIZE_GATLING = join(__dirname, 'cli', 'normalize-gatling.js');

const context: NormalizerContext = {
  testType: 'load',
  scenarioName: 'checkout',
  timestamp: '2026-05-04T00:00:00Z',
  maxUsers: 100,
  duration: '5m',
  rampUpPattern: 'linear',
  cpuUsagePercent: 72,
  memoryUsagePercent: 61,
  dbPoolUsagePercent: 44,
};

const k6Summary = {
  metrics: {
    http_req_duration: {
      values: {
        'p(50)': 120,
        'p(95)': 450,
        'p(99)': 800,
      },
    },
    http_req_failed: {
      values: {
        rate: 0.03,
      },
    },
    http_reqs: {
      values: {
        rate: 250,
        count: 1000,
      },
    },
  },
};

const gatlingSummary = {
  stats: {
    numberOfRequests: {
      total: 1000,
      ko: 25,
    },
    meanNumberOfRequestsPerSecond: {
      total: 200,
    },
    percentiles1: {
      total: 100,
    },
    percentiles3: {
      total: 400,
    },
    percentiles4: {
      total: 700,
    },
  },
};

function writeTmp(dir: string, name: string, content: unknown): string {
  const filePath = join(dir, name);
  writeFileSync(filePath, typeof content === 'string' ? content : JSON.stringify(content));
  return filePath;
}

test('normalizeK6Summary maps supported k6 summary to PerformanceInput', () => {
  const input = normalizeK6Summary(k6Summary, context);
  assert.equal(input.metadata.test_type, 'load');
  assert.equal(input.metadata.scenario_name, 'checkout');
  assert.equal(input.latency.p50, 120);
  assert.equal(input.latency.p95, 450);
  assert.equal(input.latency.p99, 800);
  assert.equal(input.throughput.requests_per_second, 250);
  assert.equal(input.errors.error_rate, 0.03);
  assert.equal(input.system.cpu_usage_percent, 72);
  assert.equal(input.optional?.db_pool_usage_percent, 44);
});

test('normalizeK6Summary throws on missing http_req_duration', () => {
  assert.throws(
    () => normalizeK6Summary({ metrics: { ...k6Summary.metrics, http_req_duration: undefined } }, context),
    /metrics\.http_req_duration/,
  );
});

test('normalizeK6Summary computes total_errors from count and error_rate', () => {
  const input = normalizeK6Summary(k6Summary, context);
  assert.equal(input.errors.total_errors, 30);
});

test('normalizeGatlingSummary maps supported Gatling summary to PerformanceInput', () => {
  const input = normalizeGatlingSummary(gatlingSummary, context);
  assert.equal(input.metadata.test_type, 'load');
  assert.equal(input.metadata.scenario_name, 'checkout');
  assert.equal(input.latency.p50, 100);
  assert.equal(input.latency.p95, 400);
  assert.equal(input.latency.p99, 700);
  assert.equal(input.throughput.requests_per_second, 200);
  assert.equal(input.errors.total_errors, 25);
  assert.equal(input.system.memory_usage_percent, 61);
});

test('normalizeGatlingSummary computes error_rate as ko / total', () => {
  const input = normalizeGatlingSummary(gatlingSummary, context);
  assert.equal(input.errors.error_rate, 0.025);
});

test('normalizeGatlingSummary handles total = 0 by setting error_rate = 0', () => {
  const input = normalizeGatlingSummary({
    stats: {
      ...gatlingSummary.stats,
      numberOfRequests: { total: 0, ko: 0 },
    },
  }, context);
  assert.equal(input.errors.error_rate, 0);
});

test('normalizeGatlingSummary throws on missing stats', () => {
  assert.throws(
    () => normalizeGatlingSummary({}, context),
    /stats/,
  );
});

test('normalize-k6 CLI happy path', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'k6-summary.json', k6Summary);
  const contextPath = writeTmp(dir, 'context.json', context);
  const result = spawnSync('node', [NORMALIZE_K6, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const input = JSON.parse(result.stdout);
  assert.equal(input.latency.p95, 450);
  assert.equal(input.errors.total_errors, 30);
});

test('normalize-gatling CLI happy path', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'gatling-summary.json', gatlingSummary);
  const contextPath = writeTmp(dir, 'context.json', context);
  const result = spawnSync('node', [NORMALIZE_GATLING, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const input = JSON.parse(result.stdout);
  assert.equal(input.latency.p95, 400);
  assert.equal(input.errors.error_rate, 0.025);
});

test('normalize-k6 CLI exits 1 on invalid JSON', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'bad-k6.json', 'not json');
  const contextPath = writeTmp(dir, 'context.json', context);
  const result = spawnSync('node', [NORMALIZE_K6, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Error reading'));
});

test('normalize-gatling CLI exits 1 on invalid context JSON', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'gatling-summary.json', gatlingSummary);
  const contextPath = writeTmp(dir, 'bad-context.json', 'not json');
  const result = spawnSync('node', [NORMALIZE_GATLING, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Error reading'));
});
