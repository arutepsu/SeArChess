import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { renderEnvironmentCheck } from '../ui/workbenchView';
import { resolveObservabilityPaths, type EnvironmentCheckResult } from './environmentCheck';

// ─── renderEnvironmentCheck: observability section ───────────────────────────

const baseResult: EnvironmentCheckResult = {
  nodeVersion: 'v20.0.0',
  platform: 'linux',
  cwd: '/project',
  configFound: true,
  resolvedArtifactRoot: '/project/docs/performance',
  baselineRunsDirExists: false,
  optimizedRunsDirExists: false,
  k6Available: false,
};

test('renderEnvironmentCheck includes Observability section when prometheus found and grafana missing', () => {
  const result: EnvironmentCheckResult = {
    ...baseResult,
    prometheusConfigExists: true,
    grafanaDirExists: false,
  };
  const output = renderEnvironmentCheck(result);
  assert.ok(output.includes('Observability'), 'should include Observability section header');
  assert.ok(output.includes('Prometheus:'), 'should include Prometheus row');
  assert.ok(output.includes('Grafana:'), 'should include Grafana row');
  assert.ok(output.includes('[ok]'), 'should show [ok] for found prometheus');
  assert.ok(output.includes('[warn]'), 'should show [warn] for missing grafana');
});

test('renderEnvironmentCheck shows [ok] for both prometheus and grafana when both found', () => {
  const result: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'linux',
    cwd: '/project',
    configFound: true,
    resolvedArtifactRoot: '/project/docs/performance',
    baselineRunsDirExists: true,
    optimizedRunsDirExists: true,
    k6Available: true,
    prometheusConfigExists: true,
    grafanaDirExists: true,
  };
  const output = renderEnvironmentCheck(result);
  assert.ok(output.includes('Observability'));
  // With everything found there should be no [warn] markers anywhere
  assert.ok(!output.includes('[warn]'), `unexpected [warn] in output:\n${output}`);
  const okCount = (output.match(/\[ok\]/g) ?? []).length;
  assert.ok(okCount >= 2, `expected at least 2 [ok] markers, got ${okCount}`);
});

test('renderEnvironmentCheck shows [warn] for both prometheus and grafana when both missing', () => {
  const result: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'linux',
    cwd: '/project',
    configFound: false,
    resolvedArtifactRoot: '/project/docs/performance',
    baselineRunsDirExists: false,
    optimizedRunsDirExists: false,
    k6Available: false,
    prometheusConfigExists: false,
    grafanaDirExists: false,
  };
  const output = renderEnvironmentCheck(result);
  assert.ok(output.includes('Observability'));
  // With everything missing there should be no [ok] markers anywhere
  assert.ok(!output.includes('[ok]'), `unexpected [ok] in output:\n${output}`);
  const warnCount = (output.match(/\[warn\]/g) ?? []).length;
  assert.ok(warnCount >= 2, `expected at least 2 [warn] markers, got ${warnCount}`);
});

test('renderEnvironmentCheck omits observability section when fields are undefined', () => {
  const output = renderEnvironmentCheck(baseResult);
  assert.ok(!output.includes('Observability'), 'should omit observability section when fields are undefined');
  assert.ok(!output.includes('Prometheus:'));
  assert.ok(!output.includes('Grafana:'));
});

// ─── resolveObservabilityPaths: path detection with temp dirs ─────────────────

test('resolveObservabilityPaths detects prometheus config when present', () => {
  const repoRoot = mkdtempSync(join(tmpdir(), 'obs-test-'));
  writeFileSync(join(repoRoot, 'performance.config.json'), '{}');

  const obsDir = join(repoRoot, 'tools', 'performance', 'observability', 'prometheus');
  mkdirSync(obsDir, { recursive: true });
  writeFileSync(join(obsDir, 'prometheus.yml'), '# prometheus config\n');

  const { prometheusConfigPath } = resolveObservabilityPaths(repoRoot);
  assert.ok(prometheusConfigPath.endsWith('prometheus.yml'), 'path should end with prometheus.yml');
  // The resolved path should point into the temp repo root
  assert.ok(prometheusConfigPath.includes('observability'), 'path should include observability dir');
});

test('resolveObservabilityPaths detects grafana dir when present', () => {
  const repoRoot = mkdtempSync(join(tmpdir(), 'obs-test-'));
  writeFileSync(join(repoRoot, 'performance.config.json'), '{}');

  const grafanaDir = join(repoRoot, 'tools', 'performance', 'observability', 'grafana');
  mkdirSync(grafanaDir, { recursive: true });

  const { grafanaDirPath } = resolveObservabilityPaths(repoRoot);
  assert.ok(grafanaDirPath.endsWith('grafana'), 'path should end with grafana');
  assert.ok(grafanaDirPath.includes('observability'), 'path should include observability dir');
});

test('resolveObservabilityPaths returns consistent paths regardless of nested startDir', () => {
  const repoRoot = mkdtempSync(join(tmpdir(), 'obs-test-'));
  writeFileSync(join(repoRoot, 'performance.config.json'), '{}');
  const nested = join(repoRoot, 'tools', 'performance', 'analysis');
  mkdirSync(nested, { recursive: true });

  const fromRoot   = resolveObservabilityPaths(repoRoot);
  const fromNested = resolveObservabilityPaths(nested);

  assert.equal(fromRoot.prometheusConfigPath, fromNested.prometheusConfigPath);
  assert.equal(fromRoot.grafanaDirPath, fromNested.grafanaDirPath);
});
