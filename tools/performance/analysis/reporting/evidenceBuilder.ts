import type { PerformanceInput, BottleneckType } from '../domain/models';

export function buildEvidence(input: PerformanceInput, bottleneckType: BottleneckType): string[] {
  const p95      = input.latency.p95;
  const cpu      = input.system.cpu_usage_percent;
  const rate     = input.errors.error_rate;
  const maxUsers = input.load_profile.max_users;

  switch (bottleneckType) {
    case 'CPU_BOUND':
      return [
        `p95 latency is ${p95}ms, exceeding the 500ms threshold`,
        `CPU usage is ${cpu}%, exceeding the 80% saturation threshold`,
      ];
    case 'IO_BOUND':
      return [
        `p95 latency is ${p95}ms, exceeding the 500ms threshold`,
        `CPU usage is ${cpu}%, below 50% — indicates external IO wait rather than CPU pressure`,
      ];
    case 'CONTENTION':
      return [
        `p95 latency is ${p95}ms, exceeding the 500ms threshold`,
        `error rate is ${rate}, exceeding the 2% threshold`,
      ];
    case 'SCALABILITY':
      return [
        `p95 latency is ${p95}ms, exceeding the 500ms threshold`,
        `max concurrent users is ${maxUsers}, at or above the 100-user threshold`,
      ];
    case 'UNKNOWN':
      return ['no rule condition matched the observed metrics'];
  }
}
