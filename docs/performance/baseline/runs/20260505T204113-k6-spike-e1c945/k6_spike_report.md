# k6 spike baseline Performance Report

## Executive Summary

Scenario k6-spike-baseline produced a SCALABILITY bottleneck classification with MEDIUM confidence.

## Performance Report

- Scenario: k6-spike-baseline
- Test type: spike
- Timestamp: 2026-05-05T20:42:27.084Z
- p95 latency: 4066.167279999999ms
- error rate: 0.00%
- throughput: 50.89200023865351 req/s
- bottleneck type: SCALABILITY
- confidence: MEDIUM

### Observations

- p50 latency is 1435.8754ms
- p95 latency is 4066.167279999999ms
- p99 latency is 5430.298892000002ms
- error rate is 0 (0 total errors)
- throughput is 50.89200023865351 requests/second
- CPU usage is 72%
- memory usage is 61%
- max concurrent users is 150

### Evidence

- p95 latency is 4066.167279999999ms, exceeding the 500ms threshold
- max concurrent users is 150, at or above the 100-user threshold

### Suggestions

- Improve architecture to support async and batching patterns
- Reduce synchronous dependencies

### Notes

- db_pool_usage_percent not provided; database connection pressure is unknown
