import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildK6ArtifactPaths,
  getK6TestConfig,
  parseRunK6Args,
  shouldContinueAfterK6Failure,
} from './cli/run-k6';
import { buildK6NormalizerContext } from './application/runK6Report';

test('run-k6 argument parser rejects missing required args', () => {
  assert.throws(
    () => parseRunK6Args(['--test', 'stress', '--base-url', 'http://localhost:10000/api']),
    /Missing required arguments/,
  );
});

test('run-k6 argument parser rejects unsupported test name', () => {
  assert.throws(
    () => parseRunK6Args([
      '--test', 'soak', 
      '--base-url', 'http://localhost:10000/api',
      '--cpu', '72',
      '--memory', '61',
      '--phase', 'baseline',
    ]),
    /--test must be one of baseline, load, stress, spike/,
  );
});

test('run-k6 argument parser rejects invalid CPU and memory range', () => {
  assert.throws(
    () => parseRunK6Args([
      '--test', 'stress',
      '--base-url', 'http://localhost:10000/api',
      '--cpu', '101',
      '--memory', '61',
      '--phase', 'baseline',
    ]),
    /--cpu must be a number between 0 and 100/,
  );

  assert.throws(
    () => parseRunK6Args([
      '--test', 'stress',
      '--base-url', 'http://localhost:10000/api',
      '--cpu', '72',
      '--memory', '-1',
      '--phase', 'baseline',
    ]),
    /--memory must be a number between 0 and 100/,
  );
});

test('run-k6 command mapping returns correct script and config for stress', () => {
  const config = getK6TestConfig('stress');
  assert.equal(config.test, 'stress');
  assert.ok(config.scriptPath.endsWith('tools\\performance\\k6\\stress_test.js') || config.scriptPath.endsWith('tools/performance/k6/stress_test.js'));
  assert.equal(config.maxUsers, 1000);
  assert.equal(config.duration, '3m');
  assert.equal(config.rampUpPattern, 'staged-ramp');
});

test('run-k6 artifact path construction uses phase default and file names', () => {
  const paths = buildK6ArtifactPaths('load', 'optimized');
  assert.ok(paths.outDir.endsWith('docs\\performance\\optimized') || paths.outDir.endsWith('docs/performance/optimized'));
  assert.ok(paths.summaryPath.endsWith('k6_load_summary.json'));
  assert.ok(paths.contextPath.endsWith('k6_load_context.json'));
  assert.ok(paths.inputPath.endsWith('k6_load_input.json'));
  assert.ok(paths.reportJsonPath.endsWith('k6_load_report.json'));
  assert.ok(paths.markdownPath.endsWith('k6_load_report.md'));
});

test('run-k6 threshold failure can continue when summary exists', () => {
  assert.equal(shouldContinueAfterK6Failure(99, true), true);
  assert.equal(shouldContinueAfterK6Failure(99, false), false);
  assert.equal(shouldContinueAfterK6Failure(0, true), false);
  assert.equal(shouldContinueAfterK6Failure(null, true), false);
});

test('runK6Report application helper builds normalizer context', () => {
  const config = getK6TestConfig('stress');
  const context = buildK6NormalizerContext({
    test: 'stress',
    baseUrl: 'http://localhost:10000/api',
    cpu: 72,
    memory: 61,
    phase: 'baseline',
  }, config);
  assert.equal(context.testType, 'stress');
  assert.equal(context.scenarioName, 'k6-stress-baseline');
  assert.equal(context.maxUsers, 1000);
  assert.equal(context.duration, '3m');
  assert.equal(context.rampUpPattern, 'staged-ramp');
  assert.equal(context.cpuUsagePercent, 72);
  assert.equal(context.memoryUsagePercent, 61);
  assert.ok(!Number.isNaN(Date.parse(context.timestamp ?? '')));
});
