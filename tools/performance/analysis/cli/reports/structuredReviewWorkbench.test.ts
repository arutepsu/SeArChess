import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { RunHistoryItem } from '../../application/artifacts/runHistory';
import { generateStructuredReview } from '../../application/reviewPerformance';
import { StubAIReviewProvider } from '../../ai/aiReviewProvider';
import {
  runDetailActionChoices,
  handleRunDetailAction,
  type RunDetailAction,
  type RunDetailActionDeps,
} from '../interactive';
import { AI_REVIEW_DISABLED_MESSAGE } from './aiReviewArtifacts';
import {
  buildReviewInputFromRun,
  generateStructuredReviewForRun,
  selectStructuredReviewMarkdown,
  type GenerateStructuredReviewForRunDeps,
} from './structuredReviewWorkbench';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function makeItem(
  kind: RunHistoryItem['kind'],
  overrides: Partial<RunHistoryItem> = {},
): RunHistoryItem {
  return {
    runId: 'run-001',
    phase: 'baseline',
    path: mkdtempSync(join(tmpdir(), 'perf-srw-')),
    kind,
    reports: [],
    htmlReports: [],
    logs: [],
    ...overrides,
  };
}

function makeDeps(
  overrides: Partial<GenerateStructuredReviewForRunDeps>,
  output: string[],
): GenerateStructuredReviewForRunDeps {
  return {
    selectMarkdown: () => 'report.md',
    readFile: () => '# Report content',
    createReader: () => new StubAIReviewProvider(),
    generate: generateStructuredReview,
    write: (text) => output.push(text),
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// runDetailActionChoices — exhaustive menu contract
// ---------------------------------------------------------------------------

const EXPECTED_RUN_DETAIL_ACTIONS = [
  'back',
  'generate-ai',
  'generate-structured-review',
  'paths',
  'preview',
  'preview-ai',
  'preview-structured-review',
] as const;

test('runDetailActionChoices contains exactly the expected action values', () => {
  const actual = runDetailActionChoices().map((c) => c.value).sort();
  const expected = [...EXPECTED_RUN_DETAIL_ACTIONS].sort();
  assert.deepEqual(actual, expected,
    `choices mismatch — actual: ${JSON.stringify(actual)}, expected: ${JSON.stringify(expected)}`);
});

test('runDetailActionChoices: generate-structured-review label mentions structured review', () => {
  const entry = runDetailActionChoices().find((c) => c.value === 'generate-structured-review');
  assert.ok(entry, 'generate-structured-review choice not found');
  assert.ok(
    entry.name.toLowerCase().includes('structured review'),
    `unexpected label: ${entry.name}`,
  );
});

test('runDetailActionChoices: preview-structured-review label mentions both preview and structured review', () => {
  const entry = runDetailActionChoices().find((c) => c.value === 'preview-structured-review');
  assert.ok(entry, 'preview-structured-review choice not found');
  const lower = entry.name.toLowerCase();
  assert.ok(lower.includes('preview'), `unexpected label: ${entry.name}`);
  assert.ok(lower.includes('structured review'), `unexpected label: ${entry.name}`);
});

// ---------------------------------------------------------------------------
// buildReviewInputFromRun — moduleName derivation
// ---------------------------------------------------------------------------

test('buildReviewInputFromRun: k6-single maps to moduleName k6', () => {
  const input = buildReviewInputFromRun(makeItem('k6-single'), '# Report');
  assert.equal(input.moduleName, 'k6');
});

test('buildReviewInputFromRun: k6-suite maps to moduleName k6', () => {
  const input = buildReviewInputFromRun(makeItem('k6-suite'), '# Suite');
  assert.equal(input.moduleName, 'k6');
});

test('buildReviewInputFromRun: gatling-single maps to moduleName gatling', () => {
  const input = buildReviewInputFromRun(makeItem('gatling-single'), '# Gatling');
  assert.equal(input.moduleName, 'gatling');
});

test('buildReviewInputFromRun: jmh-single maps to moduleName jmh', () => {
  const input = buildReviewInputFromRun(makeItem('jmh-single'), '# JMH');
  assert.equal(input.moduleName, 'jmh');
});

test('buildReviewInputFromRun: unknown kind maps to moduleName performance', () => {
  const input = buildReviewInputFromRun(makeItem('unknown'), '# Unknown');
  assert.equal(input.moduleName, 'performance');
});

// ---------------------------------------------------------------------------
// buildReviewInputFromRun — fixed fields
// ---------------------------------------------------------------------------

test('buildReviewInputFromRun: userQuestion is fixed review prompt', () => {
  const input = buildReviewInputFromRun(makeItem('k6-single'), '# Report');
  assert.equal(input.userQuestion, 'Review this performance run.');
});

test('buildReviewInputFromRun: notes include runId', () => {
  const item = makeItem('k6-single', { runId: 'my-run-abc' });
  const input = buildReviewInputFromRun(item, '# Report');
  assert.ok(
    input.notes?.some((n) => n.includes('my-run-abc')),
    `expected runId in notes, got: ${JSON.stringify(input.notes)}`,
  );
});

test('buildReviewInputFromRun: notes include phase', () => {
  const item = makeItem('k6-single', { runId: 'x', phase: 'optimized' });
  const input = buildReviewInputFromRun(item, '# Report');
  assert.ok(
    input.notes?.some((n) => n.includes('optimized')),
    `expected phase in notes, got: ${JSON.stringify(input.notes)}`,
  );
});

test('buildReviewInputFromRun: markdown content becomes reviewText', () => {
  const markdown = '# My Report\n\np95 latency: 200ms\nerror rate: 0%';
  const input = buildReviewInputFromRun(makeItem('k6-single'), markdown);
  assert.equal(input.reviewText, markdown);
});

// ---------------------------------------------------------------------------
// generateStructuredReviewForRun — missing markdown
// ---------------------------------------------------------------------------

test('generateStructuredReviewForRun: writes friendly message when no markdown report found', async () => {
  const output: string[] = [];
  const item = makeItem('k6-single');

  await generateStructuredReviewForRun(item, makeDeps({
    selectMarkdown: () => undefined,
    readFile: () => { throw new Error('should not be called'); },
    generate: async () => { throw new Error('should not be called'); },
  }, output));

  assert.ok(output.length > 0, 'expected output but got none');
  assert.ok(
    output.join('').toLowerCase().includes('no markdown report'),
    `unexpected message: ${output.join('')}`,
  );
});

// ---------------------------------------------------------------------------
// generateStructuredReviewForRun — read failure
// ---------------------------------------------------------------------------

test('generateStructuredReviewForRun: writes friendly message when report cannot be read', async () => {
  const output: string[] = [];
  const item = makeItem('k6-single');

  await generateStructuredReviewForRun(item, makeDeps({
    readFile: () => { throw new Error('ENOENT'); },
    generate: async () => { throw new Error('should not be called'); },
  }, output));

  const combined = output.join('');
  assert.ok(
    combined.toLowerCase().includes('could not read'),
    `unexpected message: ${combined}`,
  );
});

// ---------------------------------------------------------------------------
// generateStructuredReviewForRun — success path
// ---------------------------------------------------------------------------

test('generateStructuredReviewForRun: saves review_report.json and review_report.md to run path', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-success-'));
  const output: string[] = [];
  const item = makeItem('k6-single', { path: runDir });

  await generateStructuredReviewForRun(item, makeDeps({}, output));

  assert.ok(existsSync(join(runDir, 'review_report.json')), 'JSON artifact missing');
  assert.ok(existsSync(join(runDir, 'review_report.md')), 'Markdown artifact missing');
});

test('generateStructuredReviewForRun: success output includes generated confirmation', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-msg-'));
  const output: string[] = [];
  const item = makeItem('k6-single', { path: runDir });

  await generateStructuredReviewForRun(item, makeDeps({}, output));

  const combined = output.join('');
  assert.ok(
    combined.toLowerCase().includes('structured review generated'),
    `expected success message, got: ${combined}`,
  );
});

test('generateStructuredReviewForRun: success output includes JSON path', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-paths-'));
  const output: string[] = [];
  const item = makeItem('k6-single', { path: runDir });

  await generateStructuredReviewForRun(item, makeDeps({}, output));

  const combined = output.join('');
  assert.ok(
    combined.includes('review_report.json'),
    `expected JSON path in output, got: ${combined}`,
  );
});

test('generateStructuredReviewForRun: success output includes Markdown path', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-paths-md-'));
  const output: string[] = [];
  const item = makeItem('k6-single', { path: runDir });

  await generateStructuredReviewForRun(item, makeDeps({}, output));

  const combined = output.join('');
  assert.ok(
    combined.includes('review_report.md'),
    `expected Markdown path in output, got: ${combined}`,
  );
});

test('generateStructuredReviewForRun: ReviewInput passed to generate uses markdown as reviewText', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-input-'));
  const output: string[] = [];
  const item = makeItem('jmh-single', { path: runDir });
  const markdown = '# JMH Results\n\nFastest: 10us/op';

  let capturedInput: Parameters<typeof generateStructuredReview>[0] | undefined;

  await generateStructuredReviewForRun(item, makeDeps({
    readFile: () => markdown,
    generate: async (input, reader, opts) => {
      capturedInput = input;
      return generateStructuredReview(input, reader, opts);
    },
  }, output));

  assert.equal(capturedInput?.reviewText, markdown);
  assert.equal(capturedInput?.moduleName, 'jmh');
});

// ---------------------------------------------------------------------------
// runDetailActionChoices — preview-structured-review entry
// ---------------------------------------------------------------------------

test('runDetailActionChoices includes preview-structured-review value', () => {
  const values = runDetailActionChoices().map((c) => c.value);
  assert.ok(values.includes('preview-structured-review'));
});

test('runDetailActionChoices label for preview-structured-review mentions preview and structured review', () => {
  const entry = runDetailActionChoices().find((c) => c.value === 'preview-structured-review');
  assert.ok(entry, 'choice not found');
  const lower = entry.name.toLowerCase();
  assert.ok(lower.includes('preview'), `unexpected label: ${entry.name}`);
  assert.ok(lower.includes('structured review'), `unexpected label: ${entry.name}`);
});

test('runDetailActionChoices: generate-structured-review appears before preview-structured-review', () => {
  const values = runDetailActionChoices().map((c) => c.value);
  const genIdx = values.indexOf('generate-structured-review');
  const preIdx = values.indexOf('preview-structured-review');
  assert.ok(genIdx !== -1, 'generate-structured-review missing');
  assert.ok(preIdx !== -1, 'preview-structured-review missing');
  assert.ok(genIdx < preIdx, 'generate should appear before preview');
});

// ---------------------------------------------------------------------------
// selectStructuredReviewMarkdown
// ---------------------------------------------------------------------------

test('selectStructuredReviewMarkdown returns undefined when review_report.md does not exist', () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-sel-missing-'));
  const item = makeItem('k6-single', { path: runDir });
  assert.equal(selectStructuredReviewMarkdown(item), undefined);
});

test('selectStructuredReviewMarkdown returns path when review_report.md exists', () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-srw-sel-present-'));
  writeFileSync(join(runDir, 'review_report.md'), '# Structured Review');
  const item = makeItem('k6-single', { path: runDir });

  const result = selectStructuredReviewMarkdown(item);
  assert.ok(result !== undefined, 'expected a path');
  assert.ok(result.endsWith('review_report.md'), `unexpected path: ${result}`);
  assert.ok(existsSync(result), 'returned path should exist');
});

// ---------------------------------------------------------------------------
// handleRunDetailAction
// ---------------------------------------------------------------------------

const stubConfig = { outputRoot: '/tmp/perf-stub', ai: undefined } as Parameters<typeof handleRunDetailAction>[2];

function makeActionDeps(
  overrides: Partial<RunDetailActionDeps>,
  output: string[],
): RunDetailActionDeps {
  return {
    readFile: () => '# content',
    write: (text) => output.push(text),
    selectMarkdown: () => undefined,
    selectAIMarkdown: () => undefined,
    selectStructuredMarkdown: () => undefined,
    generateAI: async () => { throw new Error(AI_REVIEW_DISABLED_MESSAGE); },
    generateStructured: async () => {},
    renderPreview: (path, _content) => `preview:${path}`,
    renderPaths: (_item) => 'artifact-paths',
    ...overrides,
  };
}

test('handleRunDetailAction: back returns back', async () => {
  const result = await handleRunDetailAction('back', makeItem('k6-single'), stubConfig, makeActionDeps({}, []));
  assert.equal(result, 'back');
});

test('handleRunDetailAction: paths writes rendered paths and returns continue', async () => {
  const output: string[] = [];
  const result = await handleRunDetailAction('paths', makeItem('k6-single'), stubConfig, makeActionDeps({
    renderPaths: () => 'run-paths-output',
  }, output));
  assert.equal(result, 'continue');
  assert.ok(output.join('').includes('run-paths-output'), `expected paths output, got: ${output.join('')}`);
});

test('handleRunDetailAction: preview with no markdown writes muted message and returns continue', async () => {
  const output: string[] = [];
  const result = await handleRunDetailAction('preview', makeItem('k6-single'), stubConfig, makeActionDeps({
    selectMarkdown: () => undefined,
  }, output));
  assert.equal(result, 'continue');
  assert.ok(output.join('').toLowerCase().includes('no report'), `expected no-report message, got: ${output.join('')}`);
});

test('handleRunDetailAction: preview with markdown writes rendered preview and returns continue', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-action-preview-'));
  const output: string[] = [];
  const result = await handleRunDetailAction('preview', makeItem('k6-single', { path: runDir }), stubConfig, makeActionDeps({
    selectMarkdown: () => join(runDir, 'report.md'),
    readFile: () => '# Report',
    renderPreview: (p, _c) => `rendered:${p}`,
  }, output));
  assert.equal(result, 'continue');
  assert.ok(output.join('').includes('rendered:'), `expected rendered preview, got: ${output.join('')}`);
});

test('handleRunDetailAction: generate-structured-review calls generateStructured and returns continue', async () => {
  let called = false;
  const output: string[] = [];
  const result = await handleRunDetailAction('generate-structured-review', makeItem('k6-single'), stubConfig, makeActionDeps({
    generateStructured: async (_item) => { called = true; },
  }, output));
  assert.equal(result, 'continue');
  assert.ok(called, 'generateStructured was not called');
});

test('handleRunDetailAction: preview-structured-review with no review writes muted message and returns continue', async () => {
  const output: string[] = [];
  const result = await handleRunDetailAction('preview-structured-review', makeItem('k6-single'), stubConfig, makeActionDeps({
    selectStructuredMarkdown: () => undefined,
  }, output));
  assert.equal(result, 'continue');
  const combined = output.join('');
  assert.ok(
    combined.toLowerCase().includes('no structured review'),
    `expected no-structured-review message, got: ${combined}`,
  );
});

test('handleRunDetailAction: preview-structured-review with review writes rendered preview and returns continue', async () => {
  const runDir = mkdtempSync(join(tmpdir(), 'perf-action-sr-preview-'));
  const output: string[] = [];
  const srPath = join(runDir, 'review_report.md');
  const result = await handleRunDetailAction('preview-structured-review', makeItem('k6-single', { path: runDir }), stubConfig, makeActionDeps({
    selectStructuredMarkdown: () => srPath,
    readFile: () => '# SR',
    renderPreview: (p, _c) => `sr-rendered:${p}`,
  }, output));
  assert.equal(result, 'continue');
  assert.ok(output.join('').includes('sr-rendered:'), `expected rendered SR preview, got: ${output.join('')}`);
});

test('handleRunDetailAction: generate-ai when disabled writes muted message and returns continue', async () => {
  const output: string[] = [];
  const result = await handleRunDetailAction('generate-ai', makeItem('k6-single'), stubConfig, makeActionDeps({
    generateAI: async () => { throw new Error(AI_REVIEW_DISABLED_MESSAGE); },
  }, output));
  assert.equal(result, 'continue');
  assert.ok(
    output.join('').includes(AI_REVIEW_DISABLED_MESSAGE),
    `expected disabled message, got: ${output.join('')}`,
  );
});
