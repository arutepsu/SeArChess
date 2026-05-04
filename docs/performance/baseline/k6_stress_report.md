# k6 stress baseline Performance Report

## Executive Summary

Scenario k6-stress-baseline produced a SCALABILITY bottleneck classification with MEDIUM confidence.

## Performance Report

- Scenario: k6-stress-baseline
- Test type: stress
- Timestamp: 2026-05-04T12:01:01.660Z
- p95 latency: 1161.62558ms
- error rate: 0.00%
- throughput: 773.508381689777 req/s
- bottleneck type: SCALABILITY
- confidence: MEDIUM

### Observations

- p50 latency is 400.54115ms
- p95 latency is 1161.62558ms
- p99 latency is 1667.466440999999ms
- error rate is 0 (0 total errors)
- throughput is 773.508381689777 requests/second
- CPU usage is 72%
- memory usage is 61%
- max concurrent users is 1000

### Evidence

- p95 latency is 1161.62558ms, exceeding the 500ms threshold
- max concurrent users is 1000, at or above the 100-user threshold

### Suggestions

- Improve architecture to support async and batching patterns
- Reduce synchronous dependencies

### Notes

- db_pool_usage_percent not provided; database connection pressure is unknown
