import type { PerformanceInput } from '../domain/models';

export function buildObservations(input: PerformanceInput): string[] {
  const obs = [
    `p50 latency is ${input.latency.p50}ms`,
    `p95 latency is ${input.latency.p95}ms`,
    `p99 latency is ${input.latency.p99}ms`,
    `error rate is ${input.errors.error_rate} (${input.errors.total_errors} total errors)`,
    `throughput is ${input.throughput.requests_per_second} requests/second`,
    `CPU usage is ${input.system.cpu_usage_percent}%`,
    `memory usage is ${input.system.memory_usage_percent}%`,
    `max concurrent users is ${input.load_profile.max_users}`,
  ];

  if (input.optional !== undefined && input.optional.db_pool_usage_percent !== undefined) {
    obs.push(`DB pool usage is ${input.optional.db_pool_usage_percent}%`);
  }

  return obs;
}
