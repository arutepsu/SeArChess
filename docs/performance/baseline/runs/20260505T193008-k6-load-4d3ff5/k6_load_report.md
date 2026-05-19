# k6 load baseline Performance Report

## Executive Summary

Scenario k6-load-baseline produced a UNKNOWN bottleneck classification with LOW confidence.

## Performance Report

- Scenario: k6-load-baseline
- Test type: load
- Timestamp: 2026-05-05T19:31:09.142Z
- p95 latency: 0ms
- error rate: 100.00%
- throughput: 485.08611287332246 req/s
- bottleneck type: UNKNOWN
- confidence: LOW

### Observations

- p50 latency is 0ms
- p95 latency is 0ms
- p99 latency is 0ms
- error rate is 1 (29163 total errors)
- throughput is 485.08611287332246 requests/second
- CPU usage is 72%
- memory usage is 61%
- max concurrent users is 50

### Evidence

- no rule condition matched the observed metrics

### Suggestions

- No immediate optimization action is required for this load profile
- Add DB pool, GC, and queue metrics for deeper observability if further diagnosis is needed

### Notes

- db_pool_usage_percent not provided; database connection pressure is unknown
