# k6 load baseline Performance Report

## Executive Summary

Scenario k6-load-baseline produced a UNKNOWN bottleneck classification with LOW confidence.

## Performance Report

- Scenario: k6-load-baseline
- Test type: load
- Timestamp: 2026-05-05T20:47:47.482Z
- p95 latency: 1053.5786ms
- error rate: 0.00%
- throughput: 75.48368735733814 req/s
- bottleneck type: UNKNOWN
- confidence: LOW

### Observations

- p50 latency is 493.5898ms
- p95 latency is 1053.5786ms
- p99 latency is 1278.1947600000003ms
- error rate is 0 (0 total errors)
- throughput is 75.48368735733814 requests/second
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
