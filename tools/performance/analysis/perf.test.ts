import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const PERF = join(__dirname, 'cli', 'perf.js');

test('perf --help prints usage', () => {
  const result = spawnSync('node', [PERF, '--help'], { encoding: 'utf-8' });
  assert.equal(result.status, 0);
  assert.ok(result.stdout.includes('Usage: perf <command> [options]'));
  assert.ok(result.stdout.includes('perf k6'));
});

test('perf unknown command exits 1', () => {
  const result = spawnSync('node', [PERF, 'unknown'], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Unknown perf command: unknown'));
});

test('perf k6 argument parsing rejects missing args without running k6', () => {
  const result = spawnSync('node', [PERF, 'k6', '--test', 'load'], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Missing required arguments'));
});
