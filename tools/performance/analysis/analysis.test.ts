import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analyze, compare, SIGNIFICANCE_THRESHOLD_PERCENT } from './index';
import type { PerformanceInput, PerformanceReport } from './index';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function baseInput(overrides: Partial<PerformanceInput> = {}): PerformanceInput {
  const base: PerformanceInput = {
    metadata:     { test_type: 'load', scenario_name: 'default', timestamp: '2026-05-04T00:00:00Z' },
    load_profile: { max_users: 50, duration: '5m', ramp_up_pattern: 'linear' },
    latency:      { p50: 150, p95: 600, p99: 900 },
    throughput:   { requests_per_second: 400 },
    errors:       { error_rate: 0.01, total_errors: 40 },
    system:       { cpu_usage_percent: 65, memory_usage_percent: 50 },
  };
  return { ...base, ...overrides };
}

function makeReport(p95: number, errorRate: number, throughput: number): PerformanceReport {
  return {
    metadata:     { test_type: 'load', scenario_name: 'test', timestamp: '2026-05-04T00:00:00Z' },
    summary:      { p95_latency: p95, error_rate: errorRate, throughput },
    observations: [],
    bottleneck:   { type: 'UNKNOWN', confidence: 'LOW' },
    evidence:     [],
    suggestions:  [],
    notes:        [],
  };
}

// ---------------------------------------------------------------------------
// Rule engine — bottleneck classification
// ---------------------------------------------------------------------------

test('CPU_BOUND: high latency and CPU > 80%', () => {
  const report = analyze(baseInput({ system: { cpu_usage_percent: 85, memory_usage_percent: 60 } }));
  assert.equal(report.bottleneck.type,       'CPU_BOUND');
  assert.equal(report.bottleneck.confidence, 'HIGH');
});

test('IO_BOUND: high latency and CPU < 50%', () => {
  const report = analyze(baseInput({ system: { cpu_usage_percent: 30, memory_usage_percent: 40 } }));
  assert.equal(report.bottleneck.type,       'IO_BOUND');
  assert.equal(report.bottleneck.confidence, 'MEDIUM');
});

test('CONTENTION: high latency, mid CPU, error rate > 2%', () => {
  const report = analyze(baseInput({
    system: { cpu_usage_percent: 65, memory_usage_percent: 50 },
    errors: { error_rate: 0.05, total_errors: 200 },
  }));
  assert.equal(report.bottleneck.type,       'CONTENTION');
  assert.equal(report.bottleneck.confidence, 'HIGH');
});

test('SCALABILITY: high latency, mid CPU, low error, max_users >= 100', () => {
  const report = analyze(baseInput({
    load_profile: { max_users: 150, duration: '5m', ramp_up_pattern: 'linear' },
    system:       { cpu_usage_percent: 65, memory_usage_percent: 50 },
    errors:       { error_rate: 0.01, total_errors: 15 },
  }));
  assert.equal(report.bottleneck.type,       'SCALABILITY');
  assert.equal(report.bottleneck.confidence, 'MEDIUM');
});

test('UNKNOWN: no high latency (p95 <= 500ms)', () => {
  const report = analyze(baseInput({ latency: { p50: 80, p95: 300, p99: 450 } }));
  assert.equal(report.bottleneck.type,       'UNKNOWN');
  assert.equal(report.bottleneck.confidence, 'LOW');
});

test('UNKNOWN: high latency but mid CPU (50–80%), low error, max_users < 100', () => {
  const report = analyze(baseInput({
    load_profile: { max_users: 30, duration: '5m', ramp_up_pattern: 'linear' },
    system:       { cpu_usage_percent: 65, memory_usage_percent: 50 },
    errors:       { error_rate: 0.01, total_errors: 10 },
  }));
  assert.equal(report.bottleneck.type,       'UNKNOWN');
  assert.equal(report.bottleneck.confidence, 'LOW');
});

// Rule 1 takes priority over Rule 3 when CPU > 80 and error > 2%
test('Rule 1 (CPU_BOUND) wins over Rule 3 (CONTENTION) when CPU > 80', () => {
  const report = analyze(baseInput({
    system: { cpu_usage_percent: 90, memory_usage_percent: 60 },
    errors: { error_rate: 0.05, total_errors: 200 },
  }));
  assert.equal(report.bottleneck.type, 'CPU_BOUND');
});

// ---------------------------------------------------------------------------
// Report structure
// ---------------------------------------------------------------------------

test('report summary reflects input values', () => {
  const input  = baseInput();
  const report = analyze(input);
  assert.equal(report.summary.p95_latency, 600);
  assert.equal(report.summary.error_rate,  0.01);
  assert.equal(report.summary.throughput,  400);
});

test('report contains non-empty observations, evidence and suggestions', () => {
  const report = analyze(baseInput({ system: { cpu_usage_percent: 90, memory_usage_percent: 60 } }));
  assert.ok(report.observations.length > 0, 'observations must not be empty');
  assert.ok(report.evidence.length > 0,     'evidence must not be empty');
  assert.ok(report.suggestions.length > 0,  'suggestions must not be empty');
});

test('observations include db_pool when optional field is present', () => {
  const input = { ...baseInput(), optional: { db_pool_usage_percent: 75 } };
  const report = analyze(input);
  assert.ok(report.observations.some((o) => o.includes('DB pool usage is 75%')));
});

test('notes warn about missing db_pool_usage_percent', () => {
  const report = analyze(baseInput());
  assert.ok(report.notes.some((n) => n.includes('db_pool_usage_percent')));
});

test('notes do not mention db_pool when it is present', () => {
  const input = { ...baseInput(), optional: { db_pool_usage_percent: 60 } };
  const report = analyze(input);
  assert.ok(!report.notes.some((n) => n.includes('db_pool_usage_percent')));
});

test('missing optional field does not throw', () => {
  assert.doesNotThrow(() => analyze(baseInput()));
});

test('CPU_BOUND evidence references cpu and p95 values', () => {
  const report = analyze(baseInput({ system: { cpu_usage_percent: 90, memory_usage_percent: 60 } }));
  const evidenceText = report.evidence.join(' ');
  assert.ok(evidenceText.includes('600ms'));
  assert.ok(evidenceText.includes('90%'));
});

test('IO_BOUND suggestions include caching', () => {
  const report = analyze(baseInput({ system: { cpu_usage_percent: 30, memory_usage_percent: 40 } }));
  assert.ok(report.suggestions.some((s) => s.toLowerCase().includes('cach')));
});

// ---------------------------------------------------------------------------
// Input validation
// ---------------------------------------------------------------------------

test('validate: throws on missing required top-level field', () => {
  assert.throws(
    () => analyze({ ...baseInput(), system: undefined } as unknown),
    /Invalid PerformanceInput/
  );
});

test('validate: throws when latency.p95 is not a number', () => {
  assert.throws(
    () => analyze({ ...baseInput(), latency: { p50: 150, p95: 'fast', p99: 900 } } as unknown),
    /latency\.p95/
  );
});

test('validate: throws when latency.p50 is not a number', () => {
  assert.throws(
    () => analyze({ ...baseInput(), latency: { p50: null, p95: 600, p99: 900 } } as unknown),
    /latency\.p50/
  );
});

test('validate: throws when latency.p99 is not a number', () => {
  assert.throws(
    () => analyze({ ...baseInput(), latency: { p50: 150, p95: 600, p99: 'high' } } as unknown),
    /latency\.p99/
  );
});

test('validate: throws when throughput.requests_per_second is not a number', () => {
  assert.throws(
    () => analyze({ ...baseInput(), throughput: { requests_per_second: 'fast' } } as unknown),
    /requests_per_second/
  );
});

test('validate: throws when errors.total_errors is not a number', () => {
  assert.throws(
    () => analyze({ ...baseInput(), errors: { error_rate: 0.01, total_errors: 'many' } } as unknown),
    /total_errors/
  );
});

test('validate: throws when errors.error_rate is above 1', () => {
  assert.throws(
    () => analyze({ ...baseInput(), errors: { error_rate: 1.5, total_errors: 0 } } as unknown),
    /error_rate/
  );
});

test('validate: throws when errors.error_rate is below 0', () => {
  assert.throws(
    () => analyze({ ...baseInput(), errors: { error_rate: -0.1, total_errors: 0 } } as unknown),
    /error_rate/
  );
});

test('validate: throws when system.cpu_usage_percent is out of range', () => {
  assert.throws(
    () => analyze({ ...baseInput(), system: { cpu_usage_percent: 110, memory_usage_percent: 50 } } as unknown),
    /cpu_usage_percent/
  );
});

test('validate: throws when system.memory_usage_percent is out of range', () => {
  assert.throws(
    () => analyze({ ...baseInput(), system: { cpu_usage_percent: 65, memory_usage_percent: -5 } } as unknown),
    /memory_usage_percent/
  );
});

test('validate: throws when optional.db_pool_usage_percent is out of range', () => {
  assert.throws(
    () => analyze({ ...baseInput(), optional: { db_pool_usage_percent: 150 } } as unknown),
    /db_pool_usage_percent/
  );
});

test('validate: valid input with optional.db_pool_usage_percent present does not throw', () => {
  assert.doesNotThrow(() => analyze({ ...baseInput(), optional: { db_pool_usage_percent: 80 } }));
});

// ---------------------------------------------------------------------------
// Comparison logic
// ---------------------------------------------------------------------------

test('compare: SUCCESS when latency and error rate both decrease', () => {
  const result = compare(makeReport(600, 0.05, 500), makeReport(400, 0.02, 600));
  assert.equal(result.verdict, 'SUCCESS');
  // baselines are non-zero so these values are guaranteed to be numbers, not null
  assert.ok(result.improvement.latency_change_percent!    < 0);
  assert.ok(result.improvement.error_change_percent!      < 0);
  assert.ok(result.improvement.throughput_change_percent! > 0);
});

test('compare: REGRESSION when latency increases', () => {
  const result = compare(makeReport(400, 0.01, 500), makeReport(600, 0.01, 500));
  assert.equal(result.verdict, 'REGRESSION');
  assert.ok(result.improvement.latency_change_percent! > 0);
});

test('compare: REGRESSION when error rate increases', () => {
  const result = compare(makeReport(400, 0.01, 500), makeReport(350, 0.05, 500));
  assert.equal(result.verdict, 'REGRESSION');
  assert.ok(result.improvement.error_change_percent! > 0);
});

test('compare: NO_CHANGE when metrics are identical', () => {
  const result = compare(makeReport(400, 0.01, 500), makeReport(400, 0.01, 500));
  assert.equal(result.verdict, 'NO_CHANGE');
  assert.equal(result.improvement.latency_change_percent,    0);
  assert.equal(result.improvement.error_change_percent,      0);
  assert.equal(result.improvement.throughput_change_percent, 0);
});

test('compare: latency_change_percent is -25 for 400→300ms', () => {
  const result = compare(makeReport(400, 0.01, 500), makeReport(300, 0.01, 500));
  assert.equal(result.improvement.latency_change_percent, -25);
});

test('compare: error_change_percent is 100 for 0.01→0.02', () => {
  const result = compare(makeReport(400, 0.01, 500), makeReport(400, 0.02, 500));
  assert.equal(result.improvement.error_change_percent, 100);
});

test('compare: latency_change_percent is null when baseline p95 is 0', () => {
  const result = compare(makeReport(0, 0.01, 500), makeReport(300, 0.01, 500));
  assert.equal(result.improvement.latency_change_percent, null);
});

test('compare: interpretation has one line per metric', () => {
  const result = compare(makeReport(600, 0.05, 500), makeReport(400, 0.02, 600));
  assert.equal(result.interpretation.length, 3);
});

test('compare: baseline_summary and optimized_summary are preserved', () => {
  const baseline  = makeReport(600, 0.05, 500);
  const optimized = makeReport(400, 0.02, 600);
  const result    = compare(baseline, optimized);
  assert.deepEqual(result.baseline_summary,  baseline.summary);
  assert.deepEqual(result.optimized_summary, optimized.summary);
});

// ---------------------------------------------------------------------------
// Comparison verdict — significance threshold (default 5%)
// ---------------------------------------------------------------------------

test('significance threshold is 5', () => {
  assert.equal(SIGNIFICANCE_THRESHOLD_PERCENT, 5);
});

test('compare: SUCCESS when latency improves significantly and error rate is unchanged', () => {
  // latency 400→300ms = -25%  (significantly better)
  // error rate 0.01→0.01 = 0% (not significantly worse)
  const result = compare(makeReport(400, 0.01, 500), makeReport(300, 0.01, 500));
  assert.equal(result.verdict, 'SUCCESS');
});

test('compare: NO_CHANGE when latency improves by less than 5%', () => {
  // latency 400→392ms = -2%  (below threshold)
  const result = compare(makeReport(400, 0.01, 500), makeReport(392, 0.01, 500));
  assert.equal(result.verdict, 'NO_CHANGE');
});

test('compare: NO_CHANGE when both latency and error rate change by less than 5%', () => {
  // latency +3%, error +3% — both insignificant
  const result = compare(makeReport(400, 0.10, 500), makeReport(412, 0.103, 500));
  assert.equal(result.verdict, 'NO_CHANGE');
});

test('compare: no crash when baseline and optimized error_rate are both 0', () => {
  assert.doesNotThrow(() => compare(makeReport(400, 0, 500), makeReport(300, 0, 500)));
  const result = compare(makeReport(400, 0, 500), makeReport(400, 0, 500));
  assert.equal(typeof result.verdict, 'string');
});

test('compare: REGRESSION when latency worsens by more than 5%', () => {
  // latency 400→430ms = +7.5%  (significantly worse)
  const result = compare(makeReport(400, 0.01, 500), makeReport(430, 0.01, 500));
  assert.equal(result.verdict, 'REGRESSION');
});

test('compare: REGRESSION when error rate worsens by more than 5%', () => {
  // error 0.10→0.11 = +10%  (significantly worse)
  const result = compare(makeReport(400, 0.10, 500), makeReport(400, 0.11, 500));
  assert.equal(result.verdict, 'REGRESSION');
});

// ---------------------------------------------------------------------------
// Zero-baseline edge case (error_rate 0 → nonzero)
// ---------------------------------------------------------------------------

test('compare: REGRESSION when error_rate moves from 0 to above high-error threshold', () => {
  // percentChange(0, 0.05) = null, so significance check cannot fire.
  // The absolute guard must detect this as REGRESSION.
  const result = compare(makeReport(400, 0, 500), makeReport(400, 0.05, 500));
  assert.equal(result.verdict, 'REGRESSION');
});

test('compare: error_rate 0 → 0 does not produce REGRESSION', () => {
  const result = compare(makeReport(400, 0, 500), makeReport(400, 0, 500));
  assert.notEqual(result.verdict, 'REGRESSION');
});

test('compare: error_rate 0 → below threshold does not produce REGRESSION', () => {
  // 0 → 0.01 is below HIGH_ERROR_RATE_THRESHOLD (0.02)
  const result = compare(makeReport(400, 0, 500), makeReport(400, 0.01, 500));
  assert.notEqual(result.verdict, 'REGRESSION');
});
