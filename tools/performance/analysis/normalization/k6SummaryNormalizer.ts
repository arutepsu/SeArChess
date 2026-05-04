import type { PerformanceInput, OptionalMetrics } from '../domain/models';
import type { NormalizerContext } from './normalizerModels';
import { assertValidNormalizerContext } from './validateNormalizerContext';

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function child(value: unknown, path: string): Record<string, unknown> {
  if (!isObject(value)) {
    throw new Error(`${path} must be an object`);
  }
  return value;
}

function numberAt(root: unknown, path: string): number {
  const parts = path.split('.');
  let current: unknown = root;
  const visited: string[] = [];
  for (const part of parts) {
    const parentPath = visited.length === 0 ? 'input' : visited.join('.');
    const obj = child(current, parentPath);
    current = obj[part];
    visited.push(part);
  }
  if (typeof current !== 'number' || Number.isNaN(current)) {
    throw new Error(`${path} must be a number`);
  }
  return current;
}

function buildOptional(context: NormalizerContext): OptionalMetrics | undefined {
  return context.dbPoolUsagePercent === undefined
    ? undefined
    : { db_pool_usage_percent: context.dbPoolUsagePercent };
}

export function normalizeK6Summary(raw: unknown, context: NormalizerContext): PerformanceInput {
  assertValidNormalizerContext(context);

  const p50 = numberAt(raw, 'metrics.http_req_duration.values.p(50)');
  const p95 = numberAt(raw, 'metrics.http_req_duration.values.p(95)');
  const p99 = numberAt(raw, 'metrics.http_req_duration.values.p(99)');
  const errorRate = numberAt(raw, 'metrics.http_req_failed.values.rate');
  const requestRate = numberAt(raw, 'metrics.http_reqs.values.rate');
  const requestCount = numberAt(raw, 'metrics.http_reqs.values.count');

  return {
    metadata: {
      test_type: context.testType,
      scenario_name: context.scenarioName,
      timestamp: context.timestamp ?? new Date().toISOString(),
    },
    load_profile: {
      max_users: context.maxUsers,
      duration: context.duration,
      ramp_up_pattern: context.rampUpPattern,
    },
    latency: {
      p50,
      p95,
      p99,
    },
    throughput: {
      requests_per_second: requestRate,
    },
    errors: {
      error_rate: errorRate,
      total_errors: Math.round(requestCount * errorRate),
    },
    system: {
      cpu_usage_percent: context.cpuUsagePercent,
      memory_usage_percent: context.memoryUsagePercent,
    },
    optional: buildOptional(context),
  };
}
