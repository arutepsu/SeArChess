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

export function normalizeGatlingSummary(raw: unknown, context: NormalizerContext): PerformanceInput {
  assertValidNormalizerContext(context);

  const totalRequests = numberAt(raw, 'stats.numberOfRequests.total');
  const failedRequests = numberAt(raw, 'stats.numberOfRequests.ko');
  const throughput = numberAt(raw, 'stats.meanNumberOfRequestsPerSecond.total');
  const p50 = numberAt(raw, 'stats.percentiles1.total');
  const p95 = numberAt(raw, 'stats.percentiles3.total');
  const p99 = numberAt(raw, 'stats.percentiles4.total');

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
      requests_per_second: throughput,
    },
    errors: {
      error_rate: totalRequests === 0 ? 0 : failedRequests / totalRequests,
      total_errors: failedRequests,
    },
    system: {
      cpu_usage_percent: context.cpuUsagePercent,
      memory_usage_percent: context.memoryUsagePercent,
    },
    optional: buildOptional(context),
  };
}
