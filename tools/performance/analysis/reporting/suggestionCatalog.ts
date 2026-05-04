import type { BottleneckType } from '../domain/models';

// Record<BottleneckType, …> enforces exhaustive coverage at compile time.
export const SUGGESTIONS: Record<BottleneckType, string[]> = {
  CPU_BOUND: [
    'Optimize CPU-intensive operations',
    'Profile CPU hotspots to identify bottlenecks',
  ],
  IO_BOUND: [
    'Check database queries for slow or unindexed lookups',
    'Investigate blocking IO operations',
    'Add caching to reduce IO pressure',
  ],
  CONTENTION: [
    'Increase connection pool size',
    'Reduce contention on shared resources',
    'Introduce backpressure to limit concurrent load',
  ],
  SCALABILITY: [
    'Improve architecture to support async and batching patterns',
    'Reduce synchronous dependencies',
  ],
  UNKNOWN: [
    'No immediate optimization action is required for this load profile',
    'Add DB pool, GC, and queue metrics for deeper observability if further diagnosis is needed',
  ],
};
