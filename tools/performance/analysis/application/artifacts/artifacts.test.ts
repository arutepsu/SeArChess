import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolveArtifactRoot, resolvePhaseRunsDir } from './artifactRoot';
import { findRunHistory } from './runHistory';
import { createInteractiveRunOutDir } from './runFolder';

function writeConfig(dir: string, content: unknown): void {
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify(content));
}

function makeRunDir(base: string, phase: string, runId: string): string {
  const runDir = join(base, phase, 'runs', runId);
  mkdirSync(runDir, { recursive: true });
  return runDir;
}

test('resolveArtifactRoot with absolute outputRoot returns it directly', () => {
  const absPath = join(tmpdir(), 'abs-perf-root-test');
  assert.equal(resolveArtifactRoot({ outputRoot: absPath }), absPath);
});

test('resolveArtifactRoot resolves relative outputRoot against config root, not process.cwd()', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-art-root-'));
  writeConfig(tmpDir, { outputRoot: 'docs/performance' });

  const result = resolveArtifactRoot({ outputRoot: 'docs/performance', startDir: tmpDir });

  // Must resolve relative to the directory containing performance.config.json
  assert.equal(result, join(tmpDir, 'docs', 'performance'));
  // Must NOT be relative to process.cwd()
  assert.notEqual(result, join(process.cwd(), 'docs', 'performance'));
});

test('resolveArtifactRoot with no outputRoot returns path ending in docs/performance', () => {
  const result = resolveArtifactRoot();
  assert.ok(result.replace(/\\/g, '/').endsWith('docs/performance'));
});

test('resolvePhaseRunsDir returns <artifactRoot>/<phase>/runs', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-phase-runs-'));
  writeConfig(tmpDir, {});

  const baseline = resolvePhaseRunsDir('baseline', { outputRoot: 'out', startDir: tmpDir });
  assert.equal(baseline, join(tmpDir, 'out', 'baseline', 'runs'));

  const optimized = resolvePhaseRunsDir('optimized', { outputRoot: 'out', startDir: tmpDir });
  assert.equal(optimized, join(tmpDir, 'out', 'optimized', 'runs'));
});

test('findRunHistory returns [] when no run directories exist', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-hist-empty-'));
  assert.deepEqual(findRunHistory(tmpDir), []);
});

test('findRunHistory detects baseline k6-single run', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-hist-single-'));
  const runDir = makeRunDir(tmpDir, 'baseline', '20260504T120000-k6-baseline-aabbcc');
  writeFileSync(join(runDir, 'k6_baseline_report.md'), '# Report');

  const result = findRunHistory(tmpDir);
  assert.equal(result.length, 1);
  assert.equal(result[0].phase, 'baseline');
  assert.equal(result[0].kind, 'k6-single');
  assert.equal(result[0].runId, '20260504T120000-k6-baseline-aabbcc');
});

test('findRunHistory detects baseline k6-suite run', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-hist-suite-'));
  const runDir = makeRunDir(tmpDir, 'baseline', '20260504T130000-k6-suite-ddeeff');
  writeFileSync(join(runDir, 'k6_suite_report.md'), '# Suite');
  writeFileSync(join(runDir, 'k6_baseline_report.md'), '# Report');

  const result = findRunHistory(tmpDir);
  assert.equal(result.length, 1);
  assert.equal(result[0].kind, 'k6-suite');
});

test('findRunHistory detects optimized run', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-hist-opt-'));
  makeRunDir(tmpDir, 'optimized', '20260504T140000-k6-baseline-112233');

  const result = findRunHistory(tmpDir);
  assert.equal(result.length, 1);
  assert.equal(result[0].phase, 'optimized');
});

test('findRunHistory sorts newest first', () => {
  const tmpDir = mkdtempSync(join(tmpdir(), 'perf-hist-sort-'));
  const olderDir = makeRunDir(tmpDir, 'baseline', '20260504T100000-k6-baseline-000001');
  const newerDir = makeRunDir(tmpDir, 'baseline', '20260504T110000-k6-baseline-000002');

  utimesSync(olderDir, new Date('2026-05-04T10:00:00Z'), new Date('2026-05-04T10:00:00Z'));
  utimesSync(newerDir, new Date('2026-05-04T11:00:00Z'), new Date('2026-05-04T11:00:00Z'));

  const result = findRunHistory(tmpDir);
  assert.equal(result.length, 2);
  assert.equal(result[0].runId, '20260504T110000-k6-baseline-000002');
  assert.equal(result[1].runId, '20260504T100000-k6-baseline-000001');
});

test('createInteractiveRunOutDir includes /runs/ in path', () => {
  const out = createInteractiveRunOutDir('baseline', 'k6', 'baseline');
  assert.ok(out.replace(/\\/g, '/').includes('/runs/'));
});

test('createInteractiveRunOutDir with absolute outputRoot uses it as base', () => {
  const absBase = join(tmpdir(), 'my-perf-out');
  const out = createInteractiveRunOutDir('baseline', 'k6', 'load', absBase);
  assert.ok(out.startsWith(absBase));
  assert.ok(out.replace(/\\/g, '/').includes('/runs/'));
});
