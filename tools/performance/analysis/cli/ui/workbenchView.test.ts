import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderObservabilityHint } from './workbenchView';

test('renderObservabilityHint: includes runId in output', () => {
  const result = renderObservabilityHint('run-123', 'http://localhost:10000/api');
  assert.ok(result.includes('run-123'), `expected runId in output, got:\n${result}`);
});

test('renderObservabilityHint: includes all four performance header names', () => {
  const result = renderObservabilityHint('run-123', 'http://localhost:10000/api');
  assert.ok(result.includes('X-Performance-Run-Id'),    'missing X-Performance-Run-Id');
  assert.ok(result.includes('X-Performance-Tool'),      'missing X-Performance-Tool');
  assert.ok(result.includes('X-Performance-Workload'),  'missing X-Performance-Workload');
  assert.ok(result.includes('X-Performance-Phase'),     'missing X-Performance-Phase');
});

test('renderObservabilityHint: includes Observability section header', () => {
  const result = renderObservabilityHint('run-abc', 'http://localhost:10000/api');
  assert.ok(result.includes('Observability'));
});

test('renderObservabilityHint: includes log search hint with runId', () => {
  const result = renderObservabilityHint('run-abc', 'http://localhost:10000/api');
  assert.ok(result.includes('performanceRunId=run-abc'), `expected performanceRunId=run-abc in output, got:\n${result}`);
});

test('renderObservabilityHint: includes baseUrl as target', () => {
  const result = renderObservabilityHint('run-xyz', 'http://myserver:8080/api');
  assert.ok(result.includes('http://myserver:8080/api'), `expected baseUrl in output, got:\n${result}`);
});

test('renderObservabilityHint: includes /metrics reference', () => {
  const result = renderObservabilityHint('run-xyz', 'http://localhost:10000/api');
  assert.ok(result.includes('/metrics'), `expected /metrics reference in output, got:\n${result}`);
});
