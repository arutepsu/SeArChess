# k6 load baseline Performance Report

## Executive Summary

No bottleneck was detected under this load profile. Latency and error rate remained below thresholds.

## Performance Report

- Scenario: k6-load-baseline
- Test type: load
- Timestamp: 2026-05-04T12:10:35.174Z
- p95 latency: 73.26942000000001ms
- error rate: 0.00%
- throughput: 365.4360731460618 req/s
- bottleneck type: UNKNOWN
- confidence: LOW

### Observations

- p50 latency is 46.8733ms
- p95 latency is 73.26942000000001ms
- p99 latency is 111.98039400000003ms
- error rate is 0 (0 total errors)
- throughput is 365.4360731460618 requests/second
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
