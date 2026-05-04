import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
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

const directK6Summary = {
  metrics: {
    http_req_duration: {
      med: 111,
      'p(95)': 444,
      'p(99)': 777,
    },
    http_req_failed: {
      value: 0.04,
    },
    http_reqs: {
      rate: 333,
      count: 1250,
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

test('normalizeK6Summary maps actual direct k6 export shape to PerformanceInput', () => {
  const input = normalizeK6Summary(directK6Summary, context);
  assert.equal(input.latency.p50, 111);
  assert.equal(input.latency.p95, 444);
  assert.equal(input.latency.p99, 777);
  assert.equal(input.errors.error_rate, 0.04);
  assert.equal(input.throughput.requests_per_second, 333);
  assert.equal(input.errors.total_errors, 50);
});

test('normalizeK6Summary maps direct med to latency.p50', () => {
  const input = normalizeK6Summary(directK6Summary, context);
  assert.equal(input.latency.p50, 111);
});

test('normalizeK6Summary maps direct http_req_failed.value to error_rate', () => {
  const input = normalizeK6Summary(directK6Summary, context);
  assert.equal(input.errors.error_rate, 0.04);
});

test('normalizeK6Summary maps direct http_reqs rate and count', () => {
  const input = normalizeK6Summary(directK6Summary, context);
  assert.equal(input.throughput.requests_per_second, 333);
  assert.equal(input.errors.total_errors, 50);
});

test('normalizeK6Summary throws clear error when p99 is missing', () => {
  assert.throws(
    () => normalizeK6Summary({
      metrics: {
        ...directK6Summary.metrics,
        http_req_duration: {
          med: 111,
          'p(95)': 444,
        },
      },
    }, context),
    /k6 summary must include p\(99\)/,
  );
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

test('normalizeK6Summary throws when cpuUsagePercent is missing', () => {
  const { cpuUsagePercent: _cpuUsagePercent, ...invalidContext } = context;
  assert.throws(
    () => normalizeK6Summary(k6Summary, invalidContext as unknown as NormalizerContext),
    /context\.cpuUsagePercent must be a number between 0 and 100/,
  );
});

test('normalizeK6Summary throws when cpuUsagePercent is outside 0-100', () => {
  assert.throws(
    () => normalizeK6Summary(k6Summary, { ...context, cpuUsagePercent: 101 }),
    /context\.cpuUsagePercent must be a number between 0 and 100/,
  );
});

test('normalizeK6Summary accepts missing timestamp and generates one', () => {
  const { timestamp: _timestamp, ...contextWithoutTimestamp } = context;
  const input = normalizeK6Summary(k6Summary, contextWithoutTimestamp);
  assert.ok(!Number.isNaN(Date.parse(input.metadata.timestamp)));
});

test('normalizeK6Summary accepts missing dbPoolUsagePercent', () => {
  const { dbPoolUsagePercent: _dbPoolUsagePercent, ...contextWithoutDbPool } = context;
  const input = normalizeK6Summary(k6Summary, contextWithoutDbPool);
  assert.equal(input.optional, undefined);
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

test('normalizeGatlingSummary throws when memoryUsagePercent is missing', () => {
  const { memoryUsagePercent: _memoryUsagePercent, ...invalidContext } = context;
  assert.throws(
    () => normalizeGatlingSummary(gatlingSummary, invalidContext as unknown as NormalizerContext),
    /context\.memoryUsagePercent must be a number between 0 and 100/,
  );
});

test('normalizeGatlingSummary throws when dbPoolUsagePercent is outside 0-100', () => {
  assert.throws(
    () => normalizeGatlingSummary(gatlingSummary, { ...context, dbPoolUsagePercent: -1 }),
    /context\.dbPoolUsagePercent must be a number between 0 and 100/,
  );
});

test('normalizeGatlingSummary throws on missing stats', () => {
  assert.throws(
    () => normalizeGatlingSummary({}, context),
    /stats/,
  );
});

test('normalize-k6 CLI happy path', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'k6-summary.json', directK6Summary);
  const contextPath = writeTmp(dir, 'context.json', context);
  const result = spawnSync('node', [NORMALIZE_K6, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const input = JSON.parse(result.stdout);
  assert.equal(input.latency.p95, 444);
  assert.equal(input.errors.total_errors, 50);
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

test('normalize-k6 CLI exits 1 on invalid context shape', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'k6-summary.json', k6Summary);
  const { cpuUsagePercent: _cpuUsagePercent, ...invalidContext } = context;
  const contextPath = writeTmp(dir, 'invalid-context.json', invalidContext);
  const result = spawnSync('node', [NORMALIZE_K6, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Invalid NormalizerContext'));
  assert.ok(result.stderr.includes('context.cpuUsagePercent'));
});

test('normalize-gatling CLI exits 1 on invalid context shape', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-normalize-'));
  const summaryPath = writeTmp(dir, 'gatling-summary.json', gatlingSummary);
  const contextPath = writeTmp(dir, 'invalid-context.json', { ...context, memoryUsagePercent: 120 });
  const result = spawnSync('node', [NORMALIZE_GATLING, summaryPath, contextPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Invalid NormalizerContext'));
  assert.ok(result.stderr.includes('context.memoryUsagePercent'));
});
