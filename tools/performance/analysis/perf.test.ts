import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  buildInteractiveK6ReportOptions,
  buildInteractiveRunOutDir,
  buildInteractiveK6SuiteOptions,
  formatK6ReportProgressEvent,
  formatK6SuiteProgressEvent,
  formatRunningSpinnerText,
  k6WorkbenchActionToTest,
  k6ReportSpinnerAction,
  k6SuiteSpinnerAction,
  parsePercentAnswer,
  resolveAnswer,
} from './cli/interactive';
import {
  createInteractiveRunOutDir,
  createRunId,
} from './cli/artifacts/interactiveRunArtifacts';
import { loadPerformanceConfig } from './cli/config';
import { runPerfCli } from './cli/perf';
import {
  AI_REVIEW_DISABLED_MESSAGE,
  buildAIReviewArtifactPaths,
  buildReviewableRunBundle,
  generateAIReviewForRun,
  selectAIReviewMarkdown,
} from './cli/reports/aiReviewArtifacts';
import { findRunHistory } from './cli/reports/runHistory';
import { renderTable } from './cli/ui/table';
import {
  error,
  muted,
  section,
  success,
  title,
  warning,
} from './cli/ui/theme';
import {
  humanStatus,
  renderArtifactSummary,
  renderEnvironmentCheck,
  renderMarkdownPreview,
  renderRunArtifactPaths,
  renderRunHistoryChoiceLabel,
  renderRunHistoryDetails,
  renderRunHistoryList,
  renderSettingsView,
  renderSingleRunResult,
  renderStartupContext,
  renderWorkbenchHeader,
  selectPreferredMarkdownReport,
} from './cli/ui/workbenchView';
import {
  getCommandVersion,
  isCommandAvailable,
  runEnvironmentCheck,
  type EnvironmentCheckResult,
} from './cli/doctor/environmentCheck';
import type { BottleneckType, Confidence, PerformanceReport } from './domain/models';

const PERF = join(__dirname, 'cli', 'perf.js');

test('perf --help prints usage', () => {
  const result = spawnSync('node', [PERF, '--help'], { encoding: 'utf-8' });
  assert.equal(result.status, 0);
  assert.ok(result.stdout.includes('Usage: perf <command> [options]'));
  assert.ok(result.stdout.includes('perf k6'));
  assert.ok(result.stdout.includes('k6-suite'));
  assert.ok(result.stdout.includes('interactive'));
  assert.ok(result.stdout.includes('start'));
});

test('perf unknown command exits 1', () => {
  const result = spawnSync('node', [PERF, 'unknown'], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Unknown perf command: unknown'));
});

test('perf k6 argument parsing rejects missing args without running k6', () => {
  const cwdWithoutConfig = mkdtempSync(join(tmpdir(), 'perf-no-config-'));
  const result = spawnSync('node', [PERF, 'k6', '--test', 'load'], {
    encoding: 'utf-8',
    cwd: cwdWithoutConfig,
  });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Missing required arguments'));
});

test('perf k6-suite argument parsing rejects missing args without running k6', () => {
  const cwdWithoutConfig = mkdtempSync(join(tmpdir(), 'perf-no-config-'));
  const result = spawnSync('node', [PERF, 'k6-suite'], {
    encoding: 'utf-8',
    cwd: cwdWithoutConfig,
  });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Missing required arguments'));
});

test('perf interactive is recognized by router', async () => {
  const result = await runPerfCli(['interactive'], { runInteractive: async () => 0 });
  assert.equal(result, 0);
});

test('perf start is recognized by router', async () => {
  const result = await runPerfCli(['start'], { runInteractive: async () => 0 });
  assert.equal(result, 0);
});

test('interactive CPU and memory validation rejects invalid values', () => {
  assert.throws(
    () => parsePercentAnswer('101', 'CPU usage %'),
    /CPU usage % must be a number between 0 and 100/,
  );
  assert.throws(
    () => parsePercentAnswer('nope', 'Memory usage %'),
    /Memory usage % must be a number between 0 and 100/,
  );
});

test('interactive default resolution uses default on empty answer and explicit answer otherwise', () => {
  assert.equal(resolveAnswer('', 'baseline'), 'baseline');
  assert.equal(resolveAnswer('  ', 'baseline'), 'baseline');
  assert.equal(resolveAnswer('optimized', 'baseline'), 'optimized');
});

test('loadPerformanceConfig accepts AI settings', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-ai-config-'));
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify({
    ai: {
      enabled: true,
      provider: 'stub',
      autoReview: false,
    },
  }));

  const config = loadPerformanceConfig(dir);
  assert.deepEqual(config.ai, {
    enabled: true,
    provider: 'stub',
    autoReview: false,
  });
});

test('interactive k6 option helpers use log output mode', () => {
  const common = {
    baseUrl: 'http://localhost:10000/api',
    cpu: 72,
    memory: 61,
    phase: 'baseline' as const,
    out: 'docs/performance/baseline',
  };

  const reportOptions = buildInteractiveK6ReportOptions('load', common);
  assert.equal(reportOptions.test, 'load');
  assert.equal(reportOptions.outputMode, 'log');

  const suiteOptions = buildInteractiveK6SuiteOptions(common);
  assert.equal(suiteOptions.outputMode, 'log');
});

test('interactive k6 workbench action maps single-test actions only', () => {
  assert.equal(k6WorkbenchActionToTest('baseline'), 'baseline');
  assert.equal(k6WorkbenchActionToTest('load'), 'load');
  assert.equal(k6WorkbenchActionToTest('spike'), 'spike');
  assert.equal(k6WorkbenchActionToTest('stress'), 'stress');
  assert.equal(k6WorkbenchActionToTest('suite'), undefined);
  assert.equal(k6WorkbenchActionToTest('back'), undefined);
});

test('createRunId returns filesystem-safe id containing tool and name', () => {
  const runId = createRunId('k6-load');
  assert.match(runId, /^\d{8}T\d{6}-k6-load-[a-f0-9]{6}$/);
  assert.ok(!runId.includes(':'));
});

test('interactive run output directory includes runs folder', () => {
  const out = createInteractiveRunOutDir('baseline', 'k6', 'load', join('docs', 'performance', 'baseline'));
  const normalized = out.replace(/\\/g, '/');
  assert.ok(normalized.includes('/runs/'));
  assert.match(normalized, /\/runs\/\d{8}T\d{6}-k6-load-[a-f0-9]{6}$/);
});

test('interactive run output helper returns run id and run folder', () => {
  const result = buildInteractiveRunOutDir('optimized', 'k6', 'suite', join('docs', 'performance', 'optimized'));
  const normalized = result.out.replace(/\\/g, '/');
  assert.ok(normalized.includes('/runs/'));
  assert.ok(result.runId.includes('k6-suite'));
  assert.ok(normalized.endsWith(result.runId));
});

test('interactive report progress formatter returns success lines for analysis and markdown steps', () => {
  const analysisLine = formatK6ReportProgressEvent({
    step: 'analysis:complete',
    test: 'load',
    message: 'report generated',
    path: 'docs/performance/baseline/k6_load_report.json',
  });
  const markdownLine = formatK6ReportProgressEvent({
    step: 'markdown:written',
    test: 'load',
    message: 'markdown generated',
    path: 'docs/performance/baseline/k6_load_report.md',
  });

  assert.ok(analysisLine?.includes('report generated'));
  assert.ok(markdownLine?.includes('Markdown report generated'));
});

test('interactive report progress maps k6 execution events to spinner actions', () => {
  const start = k6ReportSpinnerAction({
    step: 'k6:start',
    test: 'load',
    message: 'start',
    path: 'docs/performance/baseline/runs/run/logs/k6_load.log',
  });
  const complete = k6ReportSpinnerAction({
    step: 'k6:complete',
    test: 'load',
    message: 'complete',
  });
  const warningAction = k6ReportSpinnerAction({
    step: 'k6:threshold-warning',
    test: 'stress',
    message: 'threshold failed',
    path: 'docs/performance/baseline/k6_stress_summary.json',
  });

  assert.deepEqual(start, {
    kind: 'start',
    text: 'Running k6 load... writing raw output to log',
    test: 'k6 load',
    logPath: 'docs/performance/baseline/runs/run/logs/k6_load.log',
  });
  assert.deepEqual(complete, { kind: 'succeed', text: 'k6 load execution completed' });
  assert.deepEqual(warningAction, { kind: 'warn', text: 'k6 stress completed with threshold warning; continuing' });
  assert.equal(formatK6ReportProgressEvent({
    step: 'k6:start',
    test: 'load',
    message: 'start',
  }), undefined);
});

test('interactive suite progress maps per-test execution to spinner actions', () => {
  const start = k6SuiteSpinnerAction({
    step: 'suite:test-start',
    test: 'baseline',
    message: 'baseline started',
  });
  const logStart = k6SuiteSpinnerAction({
    step: 'suite:test-progress',
    test: 'baseline',
    message: 'k6 started',
    reportEvent: {
      step: 'k6:start',
      test: 'baseline',
      message: 'k6 started',
      path: 'docs/performance/baseline/runs/run/logs/k6_baseline.log',
    },
  });
  const complete = k6SuiteSpinnerAction({
    step: 'suite:test-complete',
    test: 'baseline',
    message: 'baseline report generated',
  });
  const warningAction = k6SuiteSpinnerAction({
    step: 'suite:test-progress',
    test: 'stress',
    message: 'threshold warning',
    reportEvent: {
      step: 'k6:threshold-warning',
      test: 'stress',
      message: 'threshold warning',
    },
  });
  const suiteLine = formatK6SuiteProgressEvent({
    step: 'suite:markdown-written',
    message: 'suite markdown generated',
    path: 'docs/performance/baseline/k6_suite_report.md',
  });

  assert.deepEqual(start, { kind: 'start', text: 'Running baseline...', test: 'baseline' });
  assert.deepEqual(logStart, {
    kind: 'start',
    text: 'Running baseline...',
    test: 'baseline',
    logPath: 'docs/performance/baseline/runs/run/logs/k6_baseline.log',
  });
  assert.deepEqual(complete, { kind: 'succeed', text: 'baseline report generated' });
  assert.deepEqual(warningAction, { kind: 'warn', text: 'stress completed with threshold warning; continuing' });
  assert.ok(suiteLine?.includes('suite report generated'));
});

test('interactive spinner elapsed text includes elapsed seconds and log path', () => {
  const line = formatRunningSpinnerText('k6 load', 15, 'docs/performance/baseline/runs/run/logs/k6_load.log');
  assert.equal(line, 'Running k6 load... 15s elapsed. Raw output -> docs/performance/baseline/runs/run/logs/k6_load.log');
});

test('interactive spinner elapsed text works without log path', () => {
  assert.equal(formatRunningSpinnerText('load', 5), 'Running load... 5s elapsed.');
});

test('renderTable includes headers and row values', () => {
  const output = renderTable(
    ['Test', 'Bottleneck'],
    [['baseline', 'UNKNOWN']],
  );
  assert.ok(output.includes('Test'));
  assert.ok(output.includes('Bottleneck'));
  assert.ok(output.includes('baseline'));
  assert.ok(output.includes('UNKNOWN'));
});

test('theme functions return strings', () => {
  assert.equal(typeof title('Performance'), 'string');
  assert.equal(typeof section('Summary'), 'string');
  assert.equal(typeof success('ok'), 'string');
  assert.equal(typeof warning('careful'), 'string');
  assert.equal(typeof error('bad'), 'string');
  assert.equal(typeof muted('quiet'), 'string');
});

function samplePerformanceReport(
  bottleneckType: BottleneckType = 'UNKNOWN',
  confidence: Confidence = 'LOW',
): PerformanceReport {
  return {
    metadata: {
      test_type: 'load',
      scenario_name: 'k6-load-baseline',
      timestamp: '2026-05-04T12:00:00.000Z',
    },
    summary: {
      p95_latency: 54.68,
      error_rate: 0,
      throughput: 47.09,
    },
    observations: [],
    bottleneck: {
      type: bottleneckType,
      confidence,
    },
    evidence: [],
    suggestions: [],
    notes: [],
  };
}

test('workbench header includes product name', () => {
  assert.ok(renderWorkbenchHeader().includes('Searchess Performance Workbench'));
});

test('workbench human status returns Healthy for healthy UNKNOWN report', () => {
  assert.equal(humanStatus(samplePerformanceReport()), 'Healthy');
});

test('workbench human status returns scalability wording for SCALABILITY report', () => {
  assert.equal(
    humanStatus(samplePerformanceReport('SCALABILITY', 'MEDIUM')),
    'Scalability pressure detected',
  );
});

test('single-run workbench result includes p95 throughput and bottleneck', () => {
  const output = renderSingleRunResult(samplePerformanceReport());
  assert.ok(output.includes('54.68ms'));
  assert.ok(output.includes('47.09 req/s'));
  assert.ok(output.includes('UNKNOWN'));
});

test('workbench artifact summary includes report and log paths', () => {
  const output = renderArtifactSummary({
    folder: 'docs/performance/baseline/runs/run-1',
    report: 'docs/performance/baseline/runs/run-1/k6_load_report.md',
    log: 'docs/performance/baseline/runs/run-1/logs/k6_load.log',
  });
  assert.ok(output.includes('k6_load_report.md'));
  assert.ok(output.includes('logs/k6_load.log'));
});

function createHistoryRun(
  outputRoot: string,
  phase: 'baseline' | 'optimized',
  runId: string,
  files: { reports?: string[]; logs?: string[]; jsonReports?: Record<string, unknown> },
): string {
  const runPath = join(outputRoot, phase, 'runs', runId);
  mkdirSync(runPath, { recursive: true });
  for (const report of files.reports ?? []) {
    writeFileSync(join(runPath, report), '# report\n');
  }
  for (const [name, value] of Object.entries(files.jsonReports ?? {})) {
    writeFileSync(join(runPath, name), JSON.stringify(value, null, 2) + '\n');
  }
  if (files.logs && files.logs.length > 0) {
    const logsDir = join(runPath, 'logs');
    mkdirSync(logsDir, { recursive: true });
    for (const log of files.logs) {
      writeFileSync(join(logsDir, log), 'log\n');
    }
  }
  return runPath;
}

test('findRunHistory returns empty array when no run directories exist', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-history-empty-'));
  assert.deepEqual(findRunHistory(outputRoot), []);
});

test('findRunHistory detects k6-single run', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-history-single-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-940a85', {
    reports: ['k6_load_report.md'],
    logs: ['k6_load.log'],
  });

  const [item] = findRunHistory(outputRoot);
  assert.equal(item.runId, '20260504T153658-k6-load-940a85');
  assert.equal(item.phase, 'baseline');
  assert.equal(item.kind, 'k6-single');
  assert.equal(item.reports.length, 1);
  assert.equal(item.logs.length, 1);
});

test('findRunHistory detects k6-suite run', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-history-suite-'));
  createHistoryRun(outputRoot, 'optimized', '20260504T150603-k6-suite-eea403', {
    reports: ['k6_suite_report.md', 'k6_load_report.md'],
    logs: ['k6_load.log', 'k6_stress.log'],
  });

  const [item] = findRunHistory(outputRoot);
  assert.equal(item.phase, 'optimized');
  assert.equal(item.kind, 'k6-suite');
  assert.equal(item.reports.length, 2);
});

test('findRunHistory sorts newest first', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-history-sort-'));
  const older = createHistoryRun(outputRoot, 'baseline', '20260504T150603-k6-load-aaaaaa', {
    reports: ['k6_load_report.md'],
  });
  const newer = createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-stress-bbbbbb', {
    reports: ['k6_stress_report.md'],
  });
  utimesSync(older, new Date('2026-05-04T15:06:03Z'), new Date('2026-05-04T15:06:03Z'));
  utimesSync(newer, new Date('2026-05-04T15:36:58Z'), new Date('2026-05-04T15:36:58Z'));

  const history = findRunHistory(outputRoot);
  assert.equal(history[0].runId, '20260504T153658-k6-stress-bbbbbb');
  assert.equal(history[1].runId, '20260504T150603-k6-load-aaaaaa');
});

test('renderRunHistoryList includes run ID phase and type', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-history-list-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-940a85', {
    reports: ['k6_load_report.md'],
  });
  const output = renderRunHistoryList(findRunHistory(outputRoot));
  assert.ok(output.includes('20260504T153658-k6-load-940a85'));
  assert.ok(output.includes('baseline'));
  assert.ok(output.includes('k6-single'));
});

test('renderRunHistoryDetails includes folder reports and logs', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-history-details-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-940a85', {
    reports: ['k6_load_report.md'],
    logs: ['k6_load.log'],
  });
  const [item] = findRunHistory(outputRoot);
  const output = renderRunHistoryDetails(item);
  assert.ok(output.includes(item.path));
  assert.ok(output.includes('k6_load_report.md'));
  assert.ok(output.includes('k6_load.log'));
});

test('renderRunHistoryChoiceLabel includes runId, phase, kind, and report count', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-choice-label-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-940a85', {
    reports: ['k6_load_report.md'],
  });
  const [item] = findRunHistory(outputRoot);
  const label = renderRunHistoryChoiceLabel(item);
  assert.ok(label.includes('20260504T153658-k6-load-940a85'));
  assert.ok(label.includes('baseline'));
  assert.ok(label.includes('k6-single'));
  assert.ok(label.includes('1 report'));
});

test('renderRunHistoryChoiceLabel uses plural for multiple reports', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-choice-label-multi-'));
  createHistoryRun(outputRoot, 'optimized', '20260504T153658-k6-suite-aabbcc', {
    reports: ['k6_suite_report.md', 'k6_load_report.md'],
  });
  const [item] = findRunHistory(outputRoot);
  const label = renderRunHistoryChoiceLabel(item);
  assert.ok(label.includes('2 reports'));
  assert.ok(!label.includes('2 report '));
});

test('renderRunArtifactPaths includes full folder, report, and log paths', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-artifact-paths-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-940a85', {
    reports: ['k6_load_report.md'],
    logs: ['k6_load.log'],
  });
  const [item] = findRunHistory(outputRoot);
  const output = renderRunArtifactPaths(item);
  assert.ok(output.includes(item.path));
  assert.ok(output.includes('k6_load_report.md'));
  assert.ok(output.includes('k6_load.log'));
});

test('selectPreferredMarkdownReport chooses suite report for k6-suite runs', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-preferred-suite-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-suite-aabbcc', {
    reports: ['k6_baseline_report.md', 'k6_suite_report.md', 'k6_load_report.md'],
  });
  const [item] = findRunHistory(outputRoot);

  assert.ok(selectPreferredMarkdownReport(item)?.endsWith('k6_suite_report.md'));
});

test('selectPreferredMarkdownReport chooses first report for k6-single runs', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-preferred-single-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {
    reports: ['k6_load_report.md'],
  });
  const [item] = findRunHistory(outputRoot);

  assert.ok(selectPreferredMarkdownReport(item)?.endsWith('k6_load_report.md'));
});

test('selectPreferredMarkdownReport returns undefined when no reports exist', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-preferred-empty-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {});
  const [item] = findRunHistory(outputRoot);

  assert.equal(selectPreferredMarkdownReport(item), undefined);
});

test('buildReviewableRunBundle detects k6-single run', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-bundle-single-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {
    reports: ['k6_load_report.md'],
    jsonReports: {
      'k6_load_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);
  const bundle = buildReviewableRunBundle(item);

  assert.equal(bundle.tool, 'k6');
  assert.equal(bundle.kind, 'single');
  assert.equal(bundle.reportJsonPaths.length, 1);
  assert.ok(bundle.reportJsonPaths[0].endsWith('k6_load_report.json'));
  assert.equal(bundle.reportMarkdownPaths.length, 1);
});

test('buildReviewableRunBundle detects k6-suite run', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-bundle-suite-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-suite-aabbcc', {
    reports: ['k6_suite_report.md', 'k6_baseline_report.md', 'k6_load_report.md', 'k6_spike_report.md', 'k6_stress_report.md'],
    jsonReports: {
      'k6_baseline_report.json': samplePerformanceReport(),
      'k6_load_report.json': samplePerformanceReport(),
      'k6_spike_report.json': samplePerformanceReport(),
      'k6_stress_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);
  const bundle = buildReviewableRunBundle(item);

  assert.equal(bundle.tool, 'k6');
  assert.equal(bundle.kind, 'suite');
  assert.ok(bundle.suiteMarkdownPath?.endsWith('k6_suite_report.md'));
});

test('k6-suite review bundle includes all deterministic k6 report JSON files', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-bundle-suite-all-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-suite-aabbcc', {
    reports: ['k6_suite_report.md'],
    jsonReports: {
      'k6_baseline_report.json': samplePerformanceReport(),
      'k6_load_report.json': samplePerformanceReport(),
      'k6_spike_report.json': samplePerformanceReport(),
      'k6_stress_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);
  const names = buildReviewableRunBundle(item).reportJsonPaths.map((path) => path.replace(/\\/g, '/'));

  assert.equal(names.length, 4);
  assert.ok(names.some((path) => path.endsWith('k6_baseline_report.json')));
  assert.ok(names.some((path) => path.endsWith('k6_load_report.json')));
  assert.ok(names.some((path) => path.endsWith('k6_spike_report.json')));
  assert.ok(names.some((path) => path.endsWith('k6_stress_report.json')));
});

test('selectAIReviewMarkdown prefers ai_review.md when present', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-ai-preview-'));
  const runPath = createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {
    reports: ['k6_load_report.md'],
  });
  writeFileSync(join(runPath, 'ai_review.md'), '# AI Review\n');
  const [item] = findRunHistory(outputRoot);

  assert.ok(selectAIReviewMarkdown(item)?.endsWith('ai_review.md'));
});

test('selectAIReviewMarkdown prefers suite AI review for k6-suite runs', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-ai-preview-suite-'));
  const runPath = createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-suite-aabbcc', {
    reports: ['k6_suite_report.md'],
  });
  writeFileSync(join(runPath, 'ai_review.md'), '# Single AI Review\n');
  writeFileSync(join(runPath, 'ai_suite_review.md'), '# Suite AI Review\n');
  const [item] = findRunHistory(outputRoot);

  assert.ok(selectAIReviewMarkdown(item)?.endsWith('ai_suite_review.md'));
});

test('disabled AI blocks review generation', async () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-ai-disabled-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {
    jsonReports: {
      'k6_load_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);

  await assert.rejects(
    () => generateAIReviewForRun(item, {}),
    new RegExp(AI_REVIEW_DISABLED_MESSAGE.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
  );
});

test('stub AI generation writes AI review artifacts', async () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-ai-generation-'));
  const runPath = createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {
    reports: ['k6_load_report.md'],
    jsonReports: {
      'k6_load_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);
  const result = await generateAIReviewForRun(item, {
    ai: {
      enabled: true,
      provider: 'stub',
    },
  });
  const paths = buildAIReviewArtifactPaths(runPath);

  assert.equal(result.paths.jsonPath, paths.jsonPath);
  assert.equal(result.paths.markdownPath, paths.markdownPath);
  assert.ok(existsSync(paths.jsonPath));
  assert.ok(existsSync(paths.markdownPath));
  assert.ok(readFileSync(paths.markdownPath, 'utf-8').includes('## AI Review'));
});

test('stub AI generation writes suite AI review artifacts', async () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-ai-suite-generation-'));
  const runPath = createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-suite-aabbcc', {
    reports: ['k6_suite_report.md'],
    jsonReports: {
      'k6_baseline_report.json': samplePerformanceReport(),
      'k6_load_report.json': samplePerformanceReport(),
      'k6_spike_report.json': samplePerformanceReport(),
      'k6_stress_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);
  const result = await generateAIReviewForRun(item, {
    ai: {
      enabled: true,
      provider: 'stub',
    },
  });
  const paths = buildAIReviewArtifactPaths(runPath, 'suite');

  assert.equal(result.bundle.kind, 'suite');
  assert.equal(result.bundle.reportJsonPaths.length, 4);
  assert.equal(result.paths.jsonPath, paths.jsonPath);
  assert.equal(result.paths.markdownPath, paths.markdownPath);
  assert.ok(existsSync(paths.jsonPath));
  assert.ok(existsSync(paths.markdownPath));
  assert.ok(readFileSync(paths.markdownPath, 'utf-8').includes('# AI Suite Review'));
});

test('malformed deterministic report JSON fails clearly during AI generation', async () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-ai-malformed-'));
  createHistoryRun(outputRoot, 'baseline', '20260504T153658-k6-load-aabbcc', {
    reports: ['k6_load_report.md'],
    jsonReports: {
      'k6_load_report.json': { not: 'a valid performance report' },
    },
  });
  const [item] = findRunHistory(outputRoot);

  await assert.rejects(
    () => generateAIReviewForRun(item, { ai: { enabled: true, provider: 'stub' } }),
    /Invalid deterministic report/,
  );
});

test('renderMarkdownPreview includes file path and content', () => {
  const content = '# Report\n\nSome content here.';
  const output = renderMarkdownPreview('/path/to/k6_load_report.md', content);
  assert.ok(output.includes('/path/to/k6_load_report.md'));
  assert.ok(output.includes('# Report'));
  assert.ok(!output.includes('[preview truncated]'));
});

test('renderMarkdownPreview truncates content exceeding maxChars', () => {
  const longContent = 'x'.repeat(200);
  const output = renderMarkdownPreview('/some/report.md', longContent, 80, 100);
  assert.ok(output.includes('[preview truncated]'));
  assert.ok(!output.includes('x'.repeat(101)));
});

test('renderMarkdownPreview truncates content exceeding maxLines', () => {
  const manyLines = Array.from({ length: 10 }, (_, i) => `line ${i + 1}`).join('\n');
  const output = renderMarkdownPreview('/some/report.md', manyLines, 5, 8000);
  assert.ok(output.includes('[preview truncated]'));
  assert.ok(output.includes('line 5'));
  assert.ok(!output.includes('line 6'));
});

test('isCommandAvailable returns false for a nonexistent command', () => {
  assert.equal(isCommandAvailable('nonexistent-tool-xyzzy-1234'), false);
});

test('getCommandVersion returns undefined for a nonexistent command', () => {
  assert.equal(getCommandVersion('nonexistent-tool-xyzzy-1234'), undefined);
});

test('runEnvironmentCheck returns a result with the expected shape', () => {
  const result = runEnvironmentCheck();
  assert.equal(typeof result.nodeVersion, 'string');
  assert.ok(result.nodeVersion.startsWith('v'));
  assert.equal(typeof result.platform, 'string');
  assert.equal(typeof result.cwd, 'string');
  assert.equal(typeof result.configFound, 'boolean');
  assert.equal(typeof result.resolvedArtifactRoot, 'string');
  assert.equal(typeof result.baselineRunsDirExists, 'boolean');
  assert.equal(typeof result.optimizedRunsDirExists, 'boolean');
  assert.equal(typeof result.k6Available, 'boolean');
  if (result.k6Version !== undefined) {
    assert.equal(typeof result.k6Version, 'string');
  }
});

test('renderEnvironmentCheck includes Node version, platform, artifact root, and k6 status', () => {
  const result: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'win32',
    cwd: 'C:\\Users\\test',
    configFound: true,
    configPath: 'C:\\Users\\test\\performance.config.json',
    baseUrl: 'http://localhost:10000/api',
    outputRoot: 'docs/performance',
    resolvedArtifactRoot: 'C:\\Users\\test\\docs\\performance',
    baselineRunsDirExists: true,
    optimizedRunsDirExists: false,
    k6Available: true,
    k6Version: 'k6 v0.49.0 (go1.21.6, windows/amd64)',
  };
  const output = renderEnvironmentCheck(result);
  assert.ok(output.includes('v20.0.0'));
  assert.ok(output.includes('win32'));
  assert.ok(output.includes('C:\\Users\\test\\docs\\performance'));
  assert.ok(output.includes('[ok]'));
  assert.ok(output.includes('k6 v0.49.0'));
  assert.ok(output.includes('found'));
  assert.ok(output.includes('missing'));
});

test('renderEnvironmentCheck shows warn markers when config missing and k6 unavailable', () => {
  const result: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'linux',
    cwd: '/home/user/project',
    configFound: false,
    resolvedArtifactRoot: '/home/user/project/docs/performance',
    baselineRunsDirExists: false,
    optimizedRunsDirExists: false,
    k6Available: false,
  };
  const output = renderEnvironmentCheck(result);
  assert.ok(output.includes('[warn]'));
  assert.ok(output.includes('not found'));
  assert.ok(!output.includes('[ok]'));
});

test('renderStartupContext includes config status, target URL, artifact root, and k6 status', () => {
  const result: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'win32',
    cwd: 'C:\\Users\\test',
    configFound: true,
    baseUrl: 'http://localhost:10000/api',
    outputRoot: 'docs/performance',
    resolvedArtifactRoot: 'C:\\Users\\test\\docs\\performance',
    baselineRunsDirExists: true,
    optimizedRunsDirExists: false,
    k6Available: true,
  };
  const output = renderStartupContext(result);
  assert.ok(output.includes('found'));
  assert.ok(output.includes('http://localhost:10000/api'));
  assert.ok(output.includes('C:\\Users\\test\\docs\\performance'));
  assert.ok(output.includes('k6'));
});

test('renderStartupContext shows not-configured warning when baseUrl is absent', () => {
  const result: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'linux',
    cwd: '/home/test',
    configFound: false,
    resolvedArtifactRoot: '/home/test/docs/performance',
    baselineRunsDirExists: false,
    optimizedRunsDirExists: false,
    k6Available: false,
  };
  const output = renderStartupContext(result);
  assert.ok(output.includes('not configured'));
  assert.ok(output.includes('missing'));
});

test('renderSettingsView includes configured values and resolved paths', () => {
  const output = renderSettingsView({
    configFile: 'C:\\Users\\test\\performance.config.json',
    configFilePath: 'C:\\Users\\test\\performance.config.json',
    suggestedConfigFilePath: 'C:\\Users\\test\\performance.config.json',
    baseUrl: 'http://localhost:10000/api',
    outputRoot: 'docs/performance',
    artifactRoot: 'C:\\Users\\test\\docs\\performance',
    defaultPhase: 'baseline',
    cpuUsagePercent: 72,
    memoryUsagePercent: 61,
    aiEnabled: true,
    aiProvider: 'stub',
    aiAutoReview: false,
    cwd: 'C:\\Users\\test',
  });

  assert.ok(output.includes('Settings'));
  assert.ok(output.includes('Config file:'));
  assert.ok(output.includes('C:\\Users\\test\\performance.config.json'));
  assert.ok(output.includes('Base URL:'));
  assert.ok(output.includes('http://localhost:10000/api'));
  assert.ok(output.includes('Output root:'));
  assert.ok(output.includes('docs/performance'));
  assert.ok(output.includes('Artifact root:'));
  assert.ok(output.includes('C:\\Users\\test\\docs\\performance'));
  assert.ok(output.includes('Default phase:'));
  assert.ok(output.includes('baseline'));
  assert.ok(output.includes('CPU usage:'));
  assert.ok(output.includes('72'));
  assert.ok(output.includes('Memory usage:'));
  assert.ok(output.includes('61'));
  assert.ok(output.includes('AI enabled:'));
  assert.ok(output.includes('true'));
  assert.ok(output.includes('AI provider:'));
  assert.ok(output.includes('stub'));
  assert.ok(output.includes('AI auto review:'));
  assert.ok(output.includes('false'));
  assert.ok(output.includes('Current directory:'));
  assert.ok(output.includes('C:\\Users\\test'));
  assert.ok(output.includes('Edit file:'));
  assert.ok(output.includes('Example performance.config.json'));
  assert.ok(output.includes('"baseUrl": "http://localhost:10000/api"'));
  assert.ok(output.includes('"outputRoot": "docs/performance"'));
  assert.ok(output.includes('"defaultPhase": "baseline"'));
  assert.ok(output.includes('"cpuUsagePercent": 72'));
  assert.ok(output.includes('"memoryUsagePercent": 61'));
});

test('renderSettingsView handles missing config values gracefully', () => {
  const output = renderSettingsView({
    suggestedConfigFilePath: 'C:\\Users\\test\\performance.config.json',
    artifactRoot: 'C:\\Users\\test\\docs\\performance',
    cwd: 'C:\\Users\\test',
  });

  assert.ok(output.includes('Config file:       not found'));
  assert.ok(output.includes('Base URL:          not configured'));
  assert.ok(output.includes('Output root:       not configured'));
  assert.ok(output.includes('Default phase:     not configured'));
  assert.ok(output.includes('CPU usage:         not configured'));
  assert.ok(output.includes('Memory usage:      not configured'));
  assert.ok(output.includes('AI enabled:        false'));
  assert.ok(output.includes('AI provider:       stub'));
  assert.ok(output.includes('AI auto review:    false'));
  assert.ok(output.includes('Artifact root:'));
  assert.ok(output.includes('Current directory:'));
  assert.ok(output.includes('Edit file:'));
  assert.ok(output.includes('C:\\Users\\test\\performance.config.json'));
  assert.ok(output.includes('Example performance.config.json'));
});
