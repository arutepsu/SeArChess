import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  buildInteractiveK6ReportOptions,
  buildInteractiveJmhReportOptions,
  buildInteractiveRunOutDir,
  buildInteractiveK6SuiteOptions,
  formatK6ReportProgressEvent,
  formatJmhReportProgressEvent,
  formatK6SuiteProgressEvent,
  formatRunningSpinnerText,
  k6WorkbenchActionToTest,
  k6ReportSpinnerAction,
  k6SuiteSpinnerAction,
  jmhReportSpinnerAction,
  parsePercentAnswer,
  reportActionChoices,
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
import {
  readLastStructuredReviewOutputDir,
  rememberStructuredReviewOutputDir,
  structuredReviewOutputHistoryPath,
} from './cli/reports/structuredReviewOutputHistory';
import {
  buildInteractiveReviewInput,
  parseStructuredReviewNotes,
  runPreviewStructuredReviewWorkbenchFlow,
  runStructuredReviewWorkbenchFlow,
} from './cli/reports/structuredReviewWorkbench';
import { findRunHistory } from './cli/reports/runHistory';
import { renderTable } from './cli/ui/table';
import {
  error,
  muted,
  path,
  section,
  semanticStyle,
  shortenPathForDisplay,
  success,
  title,
  warning,
} from './cli/ui/theme';
import {
  blankLineAfterRun,
  displayBottleneck,
  humanStatus,
  resultBottleneckStyle,
  resultConfidenceStyle,
  resultErrorRateStyle,
  resultLatencyStyle,
  resultStatusStyle,
  renderArtifactSummary,
  renderEnvironmentCheck,
  renderMarkdownPreview,
  renderRunCompleteSummary,
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
  buildGatlingToolSummaryRows,
  buildK6ToolSummaryRows,
  renderToolSummaryFromSummary,
} from './cli/ui/toolSummaryView';
import {
  getCommandVersion,
  isCommandAvailable,
  runEnvironmentCheck,
  type EnvironmentCheckResult,
} from './cli/doctor/environmentCheck';
import type { BottleneckType, Confidence, PerformanceReport } from './domain/models';
import type { ReviewInput, ReviewReader, ReviewReport } from './ai/aiReviewModels';
import { buildK6SuiteReportHtmlPath } from './application/runK6Suite';
import { renderK6SuiteHtmlReport } from './reporting/k6SuiteHtmlBuilder';
import { jmhProfileOptions } from './application/runJmhReport';

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

test('interactive JMH options use profile defaults and workbench output folder', () => {
  const options = buildInteractiveJmhReportOptions({
    profile: 'gc',
    phase: 'baseline',
    out: 'docs/performance/baseline/runs/jmh-gc-demo',
    runId: 'jmh-gc-demo',
    pattern: 'chess.benchmarks.*',
    benchmarkGroupId: 'all',
    benchmarkGroupLabel: 'All benchmarks',
  });

  assert.equal(options.profile, 'gc');
  assert.equal(options.gcProfiler, true);
  assert.equal(options.measurementIterations, 5);
  assert.equal(options.out, 'docs/performance/baseline/runs/jmh-gc-demo');
  assert.equal(options.runId, 'jmh-gc-demo');
});

test('JMH profile defaults define smoke, baseline, and gc/allocation runs', () => {
  assert.deepEqual(jmhProfileOptions('smoke'), {
    warmupIterations: 1,
    measurementIterations: 1,
    forks: 1,
    threads: 1,
    gcProfiler: false,
    pattern: 'chess.benchmarks.*',
  });
  assert.equal(jmhProfileOptions('baseline').measurementIterations, 5);
  assert.equal(jmhProfileOptions('gc').gcProfiler, true);
});

test('JMH progress helpers render execution and artifact events', () => {
  const start = jmhReportSpinnerAction({
    step: 'jmh:start',
    profile: 'smoke',
    message: 'Starting',
    path: 'jmh_results.txt',
  });
  assert.equal(start.kind, 'start');

  assert.equal(formatJmhReportProgressEvent({
    step: 'jmh:complete',
    profile: 'smoke',
    message: 'Done',
  }), undefined);
  assert.match(formatJmhReportProgressEvent({
    step: 'structured-report:written',
    profile: 'smoke',
    message: 'Structured',
  }) ?? '', /structured report written/);
});

test('interactive JMH menu exposes real actions instead of coming soon placeholder', () => {
  const interactiveBundle = readFileSync(join(__dirname, 'cli', 'interactive.js'), 'utf-8');

  assert.match(interactiveBundle, /Run smoke JMH benchmark/);
  assert.match(interactiveBundle, /Run baseline JMH benchmark/);
  assert.match(interactiveBundle, /Run GC\/allocation JMH benchmark/);
  assert.doesNotMatch(interactiveBundle, /Coming soon: JMH benchmarks/);
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

test('structured review notes parser trims comma-separated notes', () => {
  assert.deepEqual(parseStructuredReviewNotes('alpha, beta, ,gamma'), ['alpha', 'beta', 'gamma']);
  assert.deepEqual(parseStructuredReviewNotes('   '), []);
});

test('interactive review input builder defaults empty module name', () => {
  assert.equal(buildInteractiveReviewInput({ moduleName: '   ' }).moduleName, 'performance-analysis');
});

test('interactive review input builder defaults empty review question', () => {
  assert.equal(buildInteractiveReviewInput({ userQuestion: '   ' }).userQuestion, 'Review the selected module.');
});

test('interactive review input builder trims notes and removes empty notes', () => {
  assert.deepEqual(buildInteractiveReviewInput({
    notes: ' architecture, , testing ,  ',
  }).notes, ['architecture', 'testing']);
});

test('interactive review input builder omits empty review text', () => {
  assert.equal('reviewText' in buildInteractiveReviewInput({ reviewText: '   ' }), false);
});

test('interactive review input builder preserves non-empty review text', () => {
  assert.equal(buildInteractiveReviewInput({ reviewText: '  pasted review  ' }).reviewText, 'pasted review');
});

test('reports menu exposes preview last structured review action', () => {
  assert.ok(reportActionChoices().some((choice) => choice.name === 'Preview last structured review'));
});

test('interactive structured review flow displays generated report when no output dir is provided', async () => {
  const answers = ['game', 'Review game module', 'no test summary, keep immutable', '', ''];
  const output: string[] = [];
  let capturedInput: ReviewInput | undefined;

  const report: ReviewReport = {
    summary: 'Structured review for game',
    findings: [
      {
        severity: 'info',
        category: 'architecture',
        location: 'game',
        message: 'Generated report',
        reasoning: 'The workbench action called the application use case.',
        suggestion: 'Keep using generateStructuredReview.',
      },
    ],
    suggestedNextSteps: ['Use this from the UI'],
  };

  const result = await runStructuredReviewWorkbenchFlow({}, {
    input: async () => answers.shift() ?? '',
    createReader: () => ({ readReview: async () => report } as ReviewReader),
    generate: async (input) => {
      capturedInput = input;
      return { report, saved: false };
    },
    renderMarkdown: (value) => `# Structured Review Report\n${value.summary}`,
    readLastOutputDir: () => undefined,
    rememberOutputDir: () => {
      throw new Error('should not remember unsaved preview-only output');
    },
    write: (text) => output.push(text),
  });

  assert.equal(result, 0);
  assert.equal(capturedInput?.moduleName, 'game');
  assert.equal(capturedInput?.userQuestion, 'Review game module');
  assert.deepEqual(capturedInput?.notes, ['no test summary', 'keep immutable']);
  const rendered = output.join('');
  assert.ok(rendered.includes('Structured review generated.'));
  assert.ok(rendered.includes('# Structured Review Report'));
  assert.ok(rendered.includes('Structured review for game'));
});

test('interactive structured review flow displays saved artifact paths when output dir is provided', async () => {
  const answers = ['tests', 'Review tests', '', 'review text', 'out/review'];
  const output: string[] = [];
  let capturedOutputDir: string | undefined;
  let rememberedOutputDir: string | undefined;

  const report: ReviewReport = {
    summary: 'Structured review for tests',
    findings: [],
    suggestedNextSteps: [],
  };

  const result = await runStructuredReviewWorkbenchFlow({}, {
    input: async () => answers.shift() ?? '',
    createReader: () => ({ readReview: async () => report } as ReviewReader),
    generate: async (_input, _reader, options) => {
      capturedOutputDir = options?.outputDir;
      return {
        report,
        saved: true,
        paths: {
          jsonPath: 'out/review/review_report.json',
          markdownPath: 'out/review/review_report.md',
        },
      };
    },
    renderMarkdown: (value) => `# Structured Review Report\n${value.summary}`,
    readLastOutputDir: () => undefined,
    rememberOutputDir: (outputDir) => {
      rememberedOutputDir = outputDir;
    },
    write: (text) => output.push(text),
  });

  assert.equal(result, 0);
  assert.equal(capturedOutputDir, 'out/review');
  assert.equal(rememberedOutputDir, 'out/review');
  const rendered = output.join('');
  assert.ok(rendered.includes('Structured review generated.'));
  assert.ok(rendered.includes('review_report.json'));
  assert.ok(rendered.includes('review_report.md'));
});

test('structured review output history persists last output directory', () => {
  const dir = mkdtempSync(join(tmpdir(), 'structured-review-history-'));
  writeFileSync(join(dir, 'performance.config.json'), '{}');

  rememberStructuredReviewOutputDir('out/review', dir);

  assert.equal(readLastStructuredReviewOutputDir(dir), 'out/review');
  assert.ok(existsSync(structuredReviewOutputHistoryPath(dir)));
});

test('interactive structured review flow uses the review input builder', async () => {
  const answers = ['   ', '   ', ' alpha, , beta ', '  pasted review  ', ''];
  let capturedInput: ReviewInput | undefined;
  const report: ReviewReport = {
    summary: 'Structured review',
    findings: [],
    suggestedNextSteps: [],
  };

  const result = await runStructuredReviewWorkbenchFlow({}, {
    input: async () => answers.shift() ?? '',
    createReader: () => ({ readReview: async () => report } as ReviewReader),
    generate: async (input) => {
      capturedInput = input;
      return { report, saved: false };
    },
    renderMarkdown: (value) => value.summary,
    readLastOutputDir: () => undefined,
    rememberOutputDir: () => undefined,
    write: () => undefined,
  });

  assert.equal(result, 0);
  assert.deepEqual(capturedInput, {
    moduleName: 'performance-analysis',
    userQuestion: 'Review the selected module.',
    notes: ['alpha', 'beta'],
    reviewText: 'pasted review',
  });
});

test('interactive structured review preview displays saved markdown', async () => {
  const output: string[] = [];
  const result = await runPreviewStructuredReviewWorkbenchFlow({ outputRoot: 'out/review' }, {
    input: async () => '',
    exists: (path) => path.endsWith('review_report.md'),
    read: () => '# Structured Review Report\nSaved review',
    readLastOutputDir: () => undefined,
    renderPreview: (path, content) => `PREVIEW ${path}\n${content}`,
    write: (text) => output.push(text),
  });

  assert.equal(result, 0);
  const rendered = output.join('');
  assert.ok(rendered.includes('PREVIEW'));
  assert.ok(rendered.includes('review_report.md'));
  assert.ok(rendered.includes('Saved review'));
});

test('interactive structured review preview uses remembered output directory', async () => {
  const output: string[] = [];
  let checkedPath = '';
  const result = await runPreviewStructuredReviewWorkbenchFlow({ outputRoot: 'out/default' }, {
    input: async () => '',
    exists: (path) => {
      checkedPath = path;
      return true;
    },
    read: () => '# Structured Review Report\nRemembered review',
    readLastOutputDir: () => 'out/remembered',
    renderPreview: (path, content) => `PREVIEW ${path}\n${content}`,
    write: (text) => output.push(text),
  });

  assert.equal(result, 0);
  assert.ok(checkedPath.replace(/\\/g, '/').includes('out/remembered'));
  assert.ok(output.join('').includes('Remembered review'));
});

test('interactive structured review preview falls back to default output directory', async () => {
  let checkedPath = '';
  const result = await runPreviewStructuredReviewWorkbenchFlow({ outputRoot: 'out/default' }, {
    input: async () => '',
    exists: (path) => {
      checkedPath = path;
      return true;
    },
    read: () => '# Structured Review Report\nDefault review',
    readLastOutputDir: () => undefined,
    renderPreview: (path, content) => `PREVIEW ${path}\n${content}`,
    write: () => undefined,
  });

  assert.equal(result, 0);
  assert.ok(checkedPath.replace(/\\/g, '/').includes('out/default'));
});

test('interactive structured review preview shows friendly missing artifact message', async () => {
  const output: string[] = [];
  const result = await runPreviewStructuredReviewWorkbenchFlow({ outputRoot: 'out/review' }, {
    input: async () => '',
    exists: () => false,
    read: () => {
      throw new Error('should not read missing artifact');
    },
    readLastOutputDir: () => undefined,
    renderPreview: (path, content) => `PREVIEW ${path}\n${content}`,
    write: (text) => output.push(text),
  });

  assert.equal(result, 0);
  assert.ok(output.join('').includes('No structured review artifact found. Generate a structured review first.'));
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
  assert.ok(output.includes('None detected'));
});

test('Result section renders all deterministic metrics', () => {
  const output = renderSingleRunResult(samplePerformanceReport());

  assert.ok(output.includes('Run complete:'));
  assert.ok(output.includes('Result'));
  assert.ok(output.includes('Status'));
  assert.ok(output.includes('Bottleneck'));
  assert.ok(output.includes('Diagnosis confidence'));
  assert.ok(!output.includes('Confidence'));
  assert.ok(output.includes('p95 latency'));
  assert.ok(output.includes('Error rate'));
  assert.ok(output.includes('Throughput'));
});

test('Healthy UNKNOWN bottleneck displays as none detected in CLI output', () => {
  const report = samplePerformanceReport('UNKNOWN', 'LOW');
  const output = renderSingleRunResult(report);

  assert.equal(displayBottleneck(report), 'None detected');
  assert.ok(output.includes('Bottleneck'));
  assert.ok(output.includes('None detected'));
  assert.ok(!output.includes('UNKNOWN'));
});

test('run complete summary renders compact deterministic metrics', () => {
  const output = renderRunCompleteSummary(samplePerformanceReport());

  assert.ok(output.includes('Run complete:'));
  assert.ok(output.includes('Healthy'));
  assert.ok(output.includes('p95'));
  assert.ok(output.includes('54.68ms'));
  assert.ok(output.includes('errors'));
  assert.ok(output.includes('0.00%'));
  assert.ok(output.includes('47.09 req/s'));
});

test('workbench artifact summary includes report and log paths', () => {
  const output = renderArtifactSummary({
    folder: 'docs/performance/baseline/runs/run-1',
    report: 'docs/performance/baseline/runs/run-1/k6_load_report.md',
    reportHtml: 'docs/performance/baseline/runs/run-1/k6_load_report.html',
    log: 'docs/performance/baseline/runs/run-1/logs/k6_load.log',
  });
  assert.ok(output.includes('k6_load_report.md'));
  assert.ok(output.includes('Report HTML'));
  assert.ok(output.includes('k6_load_report.html'));
  assert.ok(output.includes('logs/k6_load.log'));
});

test('workbench artifact summary renders Report HTML separately from Gatling HTML', () => {
  const output = renderArtifactSummary({
    folder: 'docs/performance/baseline/runs/run-1',
    report: 'docs/performance/baseline/runs/run-1/gatling_smoke_report.md',
    reportHtml: 'docs/performance/baseline/runs/run-1/gatling_smoke_report.html',
    htmlReport: 'tools/performance/gatling/target/gatling/searchess-run/index.html',
  });

  assert.ok(output.includes('Report HTML'));
  assert.ok(output.includes('gatling_smoke_report.html'));
  assert.ok(output.includes('Gatling HTML'));
  assert.ok(output.includes('index.html'));
  assert.ok(output.indexOf('Report HTML') < output.indexOf('Gatling HTML'));
});

test('workbench artifact summary omits missing optional artifact paths safely', () => {
  const output = renderArtifactSummary({
    folder: 'docs/performance/baseline/runs/run-1',
  });

  assert.ok(output.includes('Artifacts'));
  assert.ok(output.includes('Folder'));
  assert.ok(!output.includes('undefined'));
  assert.ok(!output.includes('Gatling HTML'));
  assert.ok(!output.includes('Log'));
});

test('semantic style maps healthy OK PASS values to success', () => {
  assert.equal(semanticStyle('Healthy'), 'success');
  assert.equal(semanticStyle('OK'), 'success');
  assert.equal(semanticStyle('PASS'), 'success');
  assert.equal(resultStatusStyle('Healthy'), 'success');
});

test('semantic style maps failed KO critical values to error', () => {
  assert.equal(semanticStyle('failed'), 'error');
  assert.equal(semanticStyle('KO'), 'error');
  assert.equal(semanticStyle('critical'), 'error');
  assert.equal(resultStatusStyle('Critical'), 'error');
});

test('LOW confidence renders through warning styling', () => {
  assert.equal(resultConfidenceStyle('LOW'), 'warning');
  assert.equal(resultConfidenceStyle('MEDIUM'), 'warning');
  assert.equal(resultConfidenceStyle('HIGH'), 'success');
});

test('UNKNOWN bottleneck renders through neutral muted styling', () => {
  assert.equal(resultBottleneckStyle('UNKNOWN'), 'muted');
});

test('Error rate coloring handles healthy warning and critical ranges', () => {
  assert.equal(resultErrorRateStyle(0), 'success');
  assert.equal(resultErrorRateStyle(0.005), 'warning');
  assert.equal(resultErrorRateStyle(0.01), 'error');
});

test('p95 latency coloring handles below near and above threshold', () => {
  assert.equal(resultLatencyStyle(100, 500), 'success');
  assert.equal(resultLatencyStyle(425, 500), 'warning');
  assert.equal(resultLatencyStyle(501, 500), 'error');
});

test('color formatting does not hide plain text when color is disabled', () => {
  const output = renderSingleRunResult(samplePerformanceReport());

  assert.ok(output.includes('Healthy'));
  assert.ok(output.includes('LOW'));
});

test('long path shortening keeps the filename visible', () => {
  const longPath = 'C:\\Users\\cgmar\\IdeaProjects\\searchess\\tools\\performance\\gatling\\target\\gatling\\searchessgameplaysimulation-20260505143544101\\index.html';
  const shortened = shortenPathForDisplay(longPath, 72);

  assert.ok(shortened.length <= 72);
  assert.ok(shortened.includes('...\\'));
  assert.ok(shortened.endsWith('index.html'));
  assert.ok(shortened.endsWith('\\index.html'));
});

test('path formatter shortens long paths without losing filename', () => {
  const longPath = 'C:\\Users\\cgmar\\IdeaProjects\\searchess\\tools\\performance\\gatling\\target\\gatling\\searchessgameplaysimulation-20260505143544101\\index.html';
  const output = path(longPath, 72);

  assert.ok(output.includes('...\\'));
  assert.ok(output.includes('index.html'));
});

test('shortened paths preserve a separator before filename', () => {
  const longPath = 'C:\\Users\\cgmar\\IdeaProjects\\searchess\\tools\\performance\\gatling\\target\\gatling\\searchessgameplaysimulation-20260505143544101\\index.html';
  const shortened = shortenPathForDisplay(longPath, 64);

  assert.match(shortened, /[\\/]index\.html$/);
  assert.ok(!shortened.includes('...index.html'));
});

test('Gatling Tool Summary renders requests total OK and KO', () => {
  const rows = buildGatlingToolSummaryRows({
    stats: {
      numberOfRequests: { total: 3900, ok: 3900, ko: 0 },
    },
  });

  assert.deepEqual(rows, [
    { metric: 'Requests', value: '3900 total / 3900 OK / 0 KO' },
  ]);
});

test('Gatling Tool Summary rounds latency and request rate to two decimals', () => {
  const rows = buildGatlingToolSummaryRows({
    stats: {
      numberOfRequests: { total: 3900, ok: 3900, ko: 0 },
      minResponseTime: { total: 4 },
      meanResponseTime: { total: 16.004 },
      maxResponseTime: { total: 109 },
      meanNumberOfRequestsPerSecond: { total: 61.904761904761905 },
    },
  });

  assert.ok(rows.some((row) => row.metric === 'Latency' && row.value === 'min 4.00ms / mean 16.00ms / max 109.00ms'));
  assert.ok(rows.some((row) => row.metric === 'Request rate' && row.value === '61.90 req/s'));
});

test('Gatling Tool Summary includes response time distribution', () => {
  const rows = buildGatlingToolSummaryRows({
    stats: {
      numberOfRequests: { total: 3900, ok: 3900, ko: 0 },
      group1: { count: 3900 },
      group2: { count: 0 },
      group3: { count: 0 },
      group4: { count: 0 },
    },
  });

  assert.ok(rows.some((row) => row.metric === 'Distribution' && row.value === '<800ms 100.00% / 800-1200ms 0.00% / >=1200ms 0.00% / failed 0.00%'));
});

test('Gatling Tool Summary includes Native report when htmlReportPath exists', () => {
  const nativePath = 'C:\\repo\\tools\\performance\\gatling\\target\\gatling\\searchessgameplaysimulation-20260505143544101\\index.html';
  const rows = buildGatlingToolSummaryRows({
    stats: {
      numberOfRequests: { total: 1, ok: 1, ko: 0 },
    },
  }, nativePath);
  const rendered = renderToolSummaryFromSummary('gatling', {
    stats: {
      numberOfRequests: { total: 1, ok: 1, ko: 0 },
    },
  }, nativePath);

  assert.ok(rows.some((row) => row.metric === 'Native report' && row.value.endsWith('index.html')));
  assert.ok(rendered?.includes('Native report'));
  assert.ok(rendered?.includes('index.html'));
});

test('Gatling Tool Summary omits Native report when htmlReportPath is missing', () => {
  const rows = buildGatlingToolSummaryRows({
    stats: {
      numberOfRequests: { total: 1, ok: 1, ko: 0 },
    },
  });

  assert.ok(!rows.some((row) => row.metric === 'Native report'));
});

test('k6 Tool Summary omits missing fields safely', () => {
  const rows = buildK6ToolSummaryRows({ metrics: { http_reqs: { values: { count: 23508 } } } });

  assert.deepEqual(rows, [
    { metric: 'Requests', value: '23508 total' },
  ]);
});

test('k6 Tool Summary renders request total and rate when present', () => {
  const rows = buildK6ToolSummaryRows({
    metrics: {
      http_reqs: { values: { count: 23508, rate: 385.514 } },
    },
  });

  assert.ok(rows.some((row) => row.metric === 'Requests' && row.value === '23508 total / 385.51 req/s'));
});

test('k6 Tool Summary renders latency percentiles when present', () => {
  const rows = buildK6ToolSummaryRows({
    metrics: {
      http_req_duration: { values: { 'p(50)': 12, 'p(95)': 52.271, 'p(99)': 90 } },
    },
  });

  assert.ok(rows.some((row) => row.metric === 'Latency' && row.value === 'p50 12.00ms / p95 52.27ms / p99 90.00ms'));
});

test('interactive output keeps deterministic Result separate from Tool Summary', () => {
  const result = renderSingleRunResult(samplePerformanceReport());
  const toolSummary = renderToolSummaryFromSummary('gatling', {
    stats: {
      numberOfRequests: { total: 3900, ok: 3900, ko: 0 },
    },
  });
  const artifacts = renderArtifactSummary({
    folder: 'docs/performance/baseline/runs/run-1',
    report: 'docs/performance/baseline/runs/run-1/gatling_load_report.md',
  });
  const output = `${result}\n\n${toolSummary ?? ''}\n\n${artifacts}${blankLineAfterRun()}? Select area`;

  assert.ok(output.includes('Result'));
  assert.ok(output.includes('Tool Summary'));
  assert.ok(output.includes('Artifacts'));
  assert.ok(output.indexOf('Result') < output.indexOf('Tool Summary'));
  assert.ok(output.indexOf('Tool Summary') < output.indexOf('Artifacts'));
  assert.ok(output.includes('Bottleneck'));
  assert.ok(output.includes('3900 total / 3900 OK / 0 KO'));
});

test('completed interactive run output leaves a blank line before next prompt', () => {
  const output = `${renderArtifactSummary({ folder: 'docs/performance/baseline/runs/run-1' })}\n${blankLineAfterRun()}? Select area`;

  assert.match(output, /\n\n\? Select area$/);
});

test('k6 suite HTML report path and renderer are available beside suite Markdown', () => {
  const outDir = join('docs', 'performance', 'baseline', 'runs', 'run-1');
  const suiteReportPath = join(outDir, 'k6_suite_report.md');
  const suiteReportHtmlPath = buildK6SuiteReportHtmlPath(outDir);
  const html = renderK6SuiteHtmlReport({
    suiteReportPath,
    suiteReportHtmlPath,
    results: [{
      test: 'load',
      report: samplePerformanceReport('UNKNOWN', 'LOW'),
      artifactPaths: {
        outDir,
        summaryPath: join(outDir, 'k6_load_summary.json'),
        contextPath: join(outDir, 'k6_load_context.json'),
        inputPath: join(outDir, 'k6_load_input.json'),
        reportJsonPath: join(outDir, 'k6_load_report.json'),
        markdownPath: join(outDir, 'k6_load_report.md'),
        reportHtmlPath: join(outDir, 'k6_load_report.html'),
        logPath: join(outDir, 'logs', 'k6_load.log'),
      },
      k6ExitCode: 0,
      continuedAfterThresholdFailure: false,
    }],
  });

  assert.ok(suiteReportHtmlPath.endsWith('k6_suite_report.html'));
  assert.ok(html.includes('k6 Suite Performance Report'));
  assert.ok(html.includes('k6_load_report.md'));
  assert.ok(html.includes('54.68ms'));
});

function createHistoryRun(
  outputRoot: string,
  phase: 'baseline' | 'optimized',
  runId: string,
  files: { reports?: string[]; htmlReports?: string[]; logs?: string[]; jsonReports?: Record<string, unknown> },
): string {
  const runPath = join(outputRoot, phase, 'runs', runId);
  mkdirSync(runPath, { recursive: true });
  for (const report of files.reports ?? []) {
    writeFileSync(join(runPath, report), '# report\n');
  }
  for (const report of files.htmlReports ?? []) {
    writeFileSync(join(runPath, report), '<!doctype html>\n');
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
    htmlReports: ['k6_load_report.html'],
    logs: ['k6_load.log'],
  });

  const [item] = findRunHistory(outputRoot);
  assert.equal(item.runId, '20260504T153658-k6-load-940a85');
  assert.equal(item.phase, 'baseline');
  assert.equal(item.kind, 'k6-single');
  assert.equal(item.reports.length, 1);
  assert.equal(item.htmlReports.length, 1);
  assert.ok(item.htmlReports[0].endsWith('k6_load_report.html'));
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
    htmlReports: ['k6_load_report.html'],
    logs: ['k6_load.log'],
  });
  const [item] = findRunHistory(outputRoot);
  const output = renderRunHistoryDetails(item);
  assert.ok(output.includes(item.path));
  assert.ok(output.includes('k6_load_report.md'));
  assert.ok(output.includes('HTML Reports'));
  assert.ok(output.includes('k6_load_report.html'));
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
    htmlReports: ['k6_load_report.html'],
    logs: ['k6_load.log'],
  });
  const [item] = findRunHistory(outputRoot);
  const output = renderRunArtifactPaths(item);
  assert.ok(output.includes(item.path));
  assert.ok(output.includes('k6_load_report.md'));
  assert.ok(output.includes('HTML Reports'));
  assert.ok(output.includes('k6_load_report.html'));
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
