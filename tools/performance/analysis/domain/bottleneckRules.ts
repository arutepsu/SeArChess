import type { PerformanceInput, Bottleneck } from './models';
import {
  HIGH_LATENCY_THRESHOLD_MS,
  HIGH_ERROR_RATE_THRESHOLD,
  HIGH_CPU_THRESHOLD,
  LOW_CPU_THRESHOLD,
  HIGH_USER_THRESHOLD,
} from './thresholds';

function isHighLatency(input: PerformanceInput): boolean {
  return input.latency.p95 > HIGH_LATENCY_THRESHOLD_MS;
}

function isHighErrorRate(input: PerformanceInput): boolean {
  return input.errors.error_rate > HIGH_ERROR_RATE_THRESHOLD;
}

/**
 * Evaluates rules in priority order and returns the first matching bottleneck.
 */
export function classifyBottleneck(input: PerformanceInput): Bottleneck {
  const highLatency = isHighLatency(input);
  const highError   = isHighErrorRate(input);
  const cpu         = input.system.cpu_usage_percent;
  const maxUsers    = input.load_profile.max_users;

  // Rule 1 — CPU_BOUND
  if (highLatency && cpu > HIGH_CPU_THRESHOLD) return { type: 'CPU_BOUND', confidence: 'HIGH' };

  // Rule 2 — IO_BOUND
  if (highLatency && cpu < LOW_CPU_THRESHOLD)  return { type: 'IO_BOUND',  confidence: 'MEDIUM' };

  // Rule 3 — CONTENTION
  if (highLatency && highError)                return { type: 'CONTENTION', confidence: 'HIGH' };

  // Rule 4 — SCALABILITY
  if (highLatency && maxUsers >= HIGH_USER_THRESHOLD) return { type: 'SCALABILITY', confidence: 'MEDIUM' };

  // Fallback
  return { type: 'UNKNOWN', confidence: 'LOW' };
}
