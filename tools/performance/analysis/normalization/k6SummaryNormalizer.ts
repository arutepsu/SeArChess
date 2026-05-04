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

function optionalNumberAt(root: unknown, path: string): number | undefined {
  try {
    return numberAt(root, path);
  } catch (_) {
    return undefined;
  }
}

function k6NumberAt(raw: unknown, directPath: string, nestedPath: string, missingMessage?: string): number {
  const direct = optionalNumberAt(raw, directPath);
  if (direct !== undefined) return direct;

  const nested = optionalNumberAt(raw, nestedPath);
  if (nested !== undefined) return nested;

  throw new Error(missingMessage ?? `${directPath} or ${nestedPath} must be a number`);
}

function buildOptional(context: NormalizerContext): OptionalMetrics | undefined {
  return context.dbPoolUsagePercent === undefined
    ? undefined
    : { db_pool_usage_percent: context.dbPoolUsagePercent };
}

export function normalizeK6Summary(raw: unknown, context: NormalizerContext): PerformanceInput {
  assertValidNormalizerContext(context);

  const p50 = k6NumberAt(raw, 'metrics.http_req_duration.med', 'metrics.http_req_duration.values.p(50)');
  const p95 = k6NumberAt(raw, 'metrics.http_req_duration.p(95)', 'metrics.http_req_duration.values.p(95)');
  const p99 = k6NumberAt(
    raw,
    'metrics.http_req_duration.p(99)',
    'metrics.http_req_duration.values.p(99)',
    'k6 summary must include p(99) at metrics.http_req_duration.p(99) or metrics.http_req_duration.values.p(99)',
  );
  const errorRate = k6NumberAt(raw, 'metrics.http_req_failed.value', 'metrics.http_req_failed.values.rate');
  const requestRate = k6NumberAt(raw, 'metrics.http_reqs.rate', 'metrics.http_reqs.values.rate');
  const requestCount = k6NumberAt(raw, 'metrics.http_reqs.count', 'metrics.http_reqs.values.count');

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
