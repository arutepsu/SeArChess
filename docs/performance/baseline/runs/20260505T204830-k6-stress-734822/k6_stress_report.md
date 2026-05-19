# k6 stress baseline Performance Report

## Executive Summary

Scenario k6-stress-baseline produced a CONTENTION bottleneck classification with HIGH confidence.

## Performance Report

- Scenario: k6-stress-baseline
- Test type: stress
- Timestamp: 2026-05-05T20:52:05.664Z
- p95 latency: 15029.7806ms
- error rate: 28.46%
- throughput: 87.58050768398265 req/s
- bottleneck type: CONTENTION
- confidence: HIGH

### Observations

- p50 latency is 2731.5254ms
- p95 latency is 15029.7806ms
- p99 latency is 15091.94172ms
- error rate is 0.28456652733293525 (5242 total errors)
- throughput is 87.58050768398265 requests/second
- CPU usage is 72%
- memory usage is 61%
- max concurrent users is 1000

### Evidence

- p95 latency is 15029.7806ms, exceeding the 500ms threshold
- error rate is 0.28456652733293525, exceeding the 2% threshold

### Suggestions

- Increase connection pool size
- Reduce contention on shared resources
- Introduce backpressure to limit concurrent load

### Notes

- db_pool_usage_percent not provided; database connection pressure is unknown
