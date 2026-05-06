import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  buildGatlingArtifactPaths,
  GATLING_HELP,
  getGatlingTestConfig,
  parseRunGatlingArgs,
} from './cli/run-gatling';
import { TOP_LEVEL_HELP } from './cli/help';
import { buildGatlingNormalizerContext, buildGatlingProcessEnv, convertGatlingGlobalStats, findGatlingHtmlReportPath } from './application/runGatlingReport';
import { normalizeGatlingSummary } from './normalization/gatlingSummaryNormalizer';
import {
  buildInteractiveGatlingReportOptions,
  formatGatlingReportProgressEvent,
  gatlingReportSpinnerAction,
} from './cli/interactive';
import { findRunHistory } from './cli/reports/runHistory';
import { buildReviewableRunBundle } from './cli/reports/aiReviewArtifacts';
import { type EnvironmentCheckResult } from './cli/doctor/environmentCheck';
import { artifactSummaryFromGatlingPaths, renderArtifactSummary, renderEnvironmentCheck } from './cli/ui/workbenchView';
import type { BottleneckType, Confidence, PerformanceReport } from './domain/models';

function samplePerformanceReport(
  bottleneckType: BottleneckType = 'UNKNOWN',
  confidence: Confidence = 'LOW',
): PerformanceReport {
  return {
    metadata: {
      test_type: 'load',
      scenario_name: 'gatling-load-baseline',
      timestamp: '2026-05-05T12:00:00.000Z',
    },
    summary: {
      p95_latency: 62.1,
      error_rate: 0.01,
      throughput: 38.4,
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

function createGatlingHistoryRun(
  outputRoot: string,
  phase: 'baseline' | 'optimized',
  runId: string,
  files: { reports?: string[]; jsonReports?: Record<string, unknown> },
): string {
  const runPath = join(outputRoot, phase, 'runs', runId);
  mkdirSync(runPath, { recursive: true });
  for (const report of files.reports ?? []) {
    writeFileSync(join(runPath, report), '# report\n');
  }
  for (const [name, value] of Object.entries(files.jsonReports ?? {})) {
    writeFileSync(join(runPath, name), JSON.stringify(value, null, 2) + '\n');
  }
  return runPath;
}

function gatlingSourcePath(...parts: string[]): string {
  return join(process.cwd(), '..', 'gatling', 'src', 'test', 'scala', 'searchess', ...parts);
}

function readGatlingSource(...parts: string[]): string {
  return readFileSync(gatlingSourcePath(...parts), 'utf-8');
}

test('run-gatling argument parser rejects unsupported argument', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-gatling-args-'));
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify({
    baseUrl: 'http://localhost:10000/api',
    cpuUsagePercent: 72,
    memoryUsagePercent: 61,
    defaultPhase: 'baseline',
  }));
  assert.throws(
    () => parseRunGatlingArgs(['--unknown-flag', 'value'], dir),
    /Unsupported argument/,
  );
});

test('run-gatling argument parser uses config defaults when no args provided', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-gatling-defaults-'));
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify({
    baseUrl: 'http://localhost:10000/api',
    cpuUsagePercent: 72,
    memoryUsagePercent: 61,
    defaultPhase: 'baseline',
  }));

  const options = parseRunGatlingArgs([], dir);
  assert.equal(options.test, 'load');
  assert.equal(options.gatlingPattern, undefined);
  assert.equal(options.baseUrl, 'http://localhost:10000/api');
  assert.equal(options.cpu, 72);
  assert.equal(options.memory, 61);
  assert.equal(options.phase, 'baseline');
});

test('run-gatling argument parser accepts named workload profiles', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-gatling-profiles-'));
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify({
    baseUrl: 'http://localhost:10000/api',
    cpuUsagePercent: 72,
    memoryUsagePercent: 61,
    defaultPhase: 'baseline',
  }));

  assert.equal(parseRunGatlingArgs(['--test', 'smoke'], dir).test, 'smoke');
  assert.equal(parseRunGatlingArgs(['--test', 'load'], dir).test, 'load');
  assert.equal(parseRunGatlingArgs(['--test', 'stress'], dir).test, 'stress');
});

test('run-gatling argument parser accepts Gatling scenario patterns', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-gatling-patterns-'));
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify({
    baseUrl: 'http://localhost:10000/api',
    cpuUsagePercent: 72,
    memoryUsagePercent: 61,
    defaultPhase: 'baseline',
  }));

  assert.equal(parseRunGatlingArgs(['--gatling-pattern', 'gameplay'], dir).gatlingPattern, 'gameplay');
  assert.equal(parseRunGatlingArgs(['--gatling-pattern', 'legalMoves'], dir).gatlingPattern, 'legalMoves');
  assert.equal(parseRunGatlingArgs(['--gatling-pattern', 'writeHeavy'], dir).gatlingPattern, 'writeHeavy');
  assert.throws(
    () => parseRunGatlingArgs(['--gatling-pattern', 'database'], dir),
    /Unknown Gatling pattern: database. Supported: all, gameplay, session, legalMoves, moveSubmission, readHeavy, writeHeavy./,
  );
});

test('run-gatling CLI args override config defaults', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-gatling-override-'));
  writeFileSync(join(dir, 'performance.config.json'), JSON.stringify({
    baseUrl: 'http://localhost:10000/api',
    cpuUsagePercent: 72,
    memoryUsagePercent: 61,
    defaultPhase: 'baseline',
  }));

  const options = parseRunGatlingArgs([
    '--base-url', 'http://localhost:9000/api',
    '--cpu', '55',
    '--memory', '48',
    '--phase', 'optimized',
  ], dir);
  assert.equal(options.baseUrl, 'http://localhost:9000/api');
  assert.equal(options.cpu, 55);
  assert.equal(options.memory, 48);
  assert.equal(options.phase, 'optimized');
});

test('run-gatling argument parser rejects missing required args when no config exists', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-gatling-no-config-'));
  assert.throws(
    () => parseRunGatlingArgs([], dir),
    /Missing required arguments/,
  );
});

test('run-gatling artifact path construction uses load prefix and phase structure', () => {
  const paths = buildGatlingArtifactPaths('load', 'baseline');
  assert.ok(paths.outDir.endsWith('docs\\performance\\baseline') || paths.outDir.endsWith('docs/performance/baseline'));
  assert.ok(paths.summaryPath.endsWith('gatling_load_summary.json'));
  assert.ok(paths.contextPath.endsWith('gatling_load_context.json'));
  assert.ok(paths.inputPath.endsWith('gatling_load_input.json'));
  assert.ok(paths.reportJsonPath.endsWith('gatling_load_report.json'));
  assert.ok(paths.markdownPath.endsWith('gatling_load_report.md'));
  assert.ok(paths.reportHtmlPath.endsWith('gatling_load_report.html'));
  assert.ok(paths.logPath.endsWith('gatling_load.log'));
});

test('Gatling native HTML report path is exposed when index.html exists', () => {
  const resultDir = mkdtempSync(join(tmpdir(), 'perf-gatling-result-'));
  writeFileSync(join(resultDir, 'index.html'), '<html></html>\n');

  const htmlReportPath = findGatlingHtmlReportPath(resultDir);

  assert.ok(htmlReportPath?.endsWith('index.html'));
});

test('Workbench Gatling artifact summary includes native HTML report when provided', () => {
  const paths = {
    ...buildGatlingArtifactPaths('load', 'baseline'),
    htmlReportPath: 'C:\\repo\\tools\\performance\\gatling\\target\\gatling\\searchess\\index.html',
  };

  const summary = artifactSummaryFromGatlingPaths(paths);
  const output = renderArtifactSummary(summary);

  assert.equal(summary.htmlReport, paths.htmlReportPath);
  assert.equal(summary.reportHtml, paths.reportHtmlPath);
  assert.ok(output.includes('Report HTML'));
  assert.ok(output.includes('Gatling HTML'));
  assert.ok(output.includes('index.html'));
});

test('run-gatling getGatlingTestConfig returns load config', () => {
  const config = getGatlingTestConfig('load');
  assert.equal(config.test, 'load');
  assert.equal(config.simulationClass, 'searchess.simulations.SearchessGameplaySimulation');
  assert.equal(config.maxUsers, 50);
  assert.equal(config.duration, '1m');
  assert.equal(config.rampUpPattern, 'linear');
});

test('run-gatling getGatlingTestConfig returns smoke and stress configs', () => {
  const smoke = getGatlingTestConfig('smoke');
  const stress = getGatlingTestConfig('stress');

  assert.equal(smoke.test, 'smoke');
  assert.equal(smoke.simulationClass, 'searchess.simulations.SearchessGameplaySimulation');
  assert.equal(smoke.maxUsers, 3);
  assert.equal(stress.test, 'stress');
  assert.equal(stress.simulationClass, 'searchess.simulations.SearchessGameplaySimulation');
  assert.equal(stress.maxUsers, 100);
});

test('run-gatling process env passes selected workload and Gatling pattern to Gatling', () => {
  const env = buildGatlingProcessEnv({
    test: 'stress',
    gatlingPattern: 'writeHeavy',
    baseUrl: 'http://localhost:8080',
    cpu: 72,
    memory: 61,
    phase: 'baseline',
    out: join('docs', 'performance', 'baseline', 'runs', '20260505T120000-gatling-stress-abc123'),
  });

  assert.equal(env.BASE_URL, 'http://localhost:8080');
  assert.equal(env.GATLING_BASE_URL, 'http://localhost:8080');
  assert.equal(env.GATLING_RUN_ID, '20260505T120000-gatling-stress-abc123');
  assert.equal(env.GATLING_TOOL, 'gatling');
  assert.equal(env.GATLING_WORKLOAD, 'stress');
  assert.equal(env.GATLING_PATTERN, 'writeHeavy');
  assert.equal(env.GATLING_PHASE, 'baseline');
});

test('Gatling simulation source demonstrates compositional Scala scenario structure', () => {
  const source = readGatlingSource('simulations', 'SearchessGameplaySimulation.scala');
  const scenarios = readGatlingSource('scenarios', 'GatlingScenarioPatterns.scala');
  const chains = readGatlingSource('chains', 'GameplayChains.scala');
  const feeders = readGatlingSource('feeders', 'SearchessFeeders.scala');
  const workloads = readGatlingSource('workloads', 'WorkloadProfiles.scala');

  assert.ok(source.includes('class SearchessGameplaySimulation'));
  assert.ok(source.includes('GatlingScenarioPatterns.choose(GatlingConfig.scenarioPattern)'));
  assert.ok(scenarios.includes('GameplayChains.createSession'));
  assert.ok(scenarios.includes('GameplayChains.completeGameplayFlow'));
  assert.ok(scenarios.includes('case "legalMoves"'));
  assert.ok(scenarios.includes('case "writeHeavy"'));
  assert.ok(chains.includes('val createSession'));
  assert.ok(chains.includes('def fetchLegalMoves'));
  assert.ok(chains.includes('def submitMove'));
  assert.ok(chains.includes('def fetchUpdatedState'));
  assert.ok(chains.includes('def gameplayTurn'));
  assert.ok(chains.includes('val readHeavyFlow'));
  assert.ok(chains.includes('val writeHeavyFlow'));
  assert.ok(feeders.includes('csv("searchess/session_modes.csv").circular'));
  assert.ok(workloads.includes('rampUsers(GatlingConfig.loadRampUsers)'));
  assert.ok(workloads.includes('constantUsersPerSec(GatlingConfig.loadUsersPerSecond)'));
});

test('Gatling workload profiles expose smoke load and stress workload selection', () => {
  const config = readGatlingSource('config', 'GatlingConfig.scala');
  const workloads = readGatlingSource('workloads', 'WorkloadProfiles.scala');

  assert.ok(config.includes('"searchess.gatling.workload"'));
  assert.ok(config.includes('"searchess.gatling.pattern"'));
  assert.ok(config.includes('"GATLING_WORKLOAD"'));
  assert.ok(config.includes('"GATLING_PATTERN"'));
  assert.ok(config.includes('val workloadProfile'));
  assert.ok(config.includes('val scenarioPattern'));
  assert.ok(workloads.includes('case "smoke"'));
  assert.ok(workloads.includes('GatlingConfig.smokeUsers'));
  assert.ok(workloads.includes('case "load"'));
  assert.ok(workloads.includes('GatlingConfig.loadRampUsers'));
  assert.ok(workloads.includes('GatlingConfig.loadUsersPerSecond'));
  assert.ok(workloads.includes('case "stress"'));
  assert.ok(workloads.includes('GatlingConfig.stressRampUsers'));
  assert.ok(workloads.includes('GatlingConfig.stressUsersPerSecond'));
});

test('Gatling simulation source includes performance correlation headers', () => {
  const source = readGatlingSource('simulations', 'SearchessGameplaySimulation.scala');
  const config = readGatlingSource('config', 'GatlingConfig.scala');

  assert.ok(config.includes('"GATLING_RUN_ID"'));
  assert.ok(config.includes('"GATLING_TOOL"'));
  assert.ok(config.includes('"GATLING_WORKLOAD"'));
  assert.ok(config.includes('"GATLING_PATTERN"'));
  assert.ok(config.includes('"GATLING_PHASE"'));
  assert.ok(source.includes('.header("X-Performance-Run-Id", GatlingConfig.performanceRunId)'));
  assert.ok(source.includes('.header("X-Performance-Tool", GatlingConfig.performanceTool)'));
  assert.ok(source.includes('.header("X-Performance-Workload", GatlingConfig.workloadProfile)'));
  assert.ok(source.includes('.header("X-Performance-Phase", GatlingConfig.performancePhase)'));
});

test('Gatling workload and scenario selectors fail clearly for unknown values', () => {
  const workloads = readGatlingSource('workloads', 'WorkloadProfiles.scala');
  const scenarios = readGatlingSource('scenarios', 'GatlingScenarioPatterns.scala');

  assert.ok(workloads.includes('Unknown Gatling workload:'));
  assert.ok(workloads.includes('Supported:'));
  assert.ok(workloads.includes('smoke'));
  assert.ok(workloads.includes('load'));
  assert.ok(workloads.includes('stress'));
  assert.ok(scenarios.includes('Unknown Gatling pattern:'));
  assert.ok(scenarios.includes('all'));
  assert.ok(scenarios.includes('gameplay'));
  assert.ok(scenarios.includes('writeHeavy'));
});

test('Gatling simulation source includes quality-gate assertions', () => {
  const source = readGatlingSource('simulations', 'SearchessGameplaySimulation.scala');

  assert.ok(source.includes('global.failedRequests.percent.lt(1.0)'));
  assert.ok(source.includes('global.responseTime.percentile3.lt(500)'));
});

test('Gatling simulation source groups gameplay phases for native reports', () => {
  const source = readGatlingSource('chains', 'GameplayChains.scala');

  assert.ok(source.includes('group("Create session")'));
  assert.ok(source.includes('group("Fetch legal moves")'));
  assert.ok(source.includes('group("Submit move")'));
  assert.ok(source.includes('group("Fetch updated state")'));
  assert.ok(source.includes('group("Gameplay turn")'));
});

test('Gatling simulation source includes semantic JSON checks', () => {
  const source = readGatlingSource('requests', 'SearchessRequests.scala');

  assert.ok(source.includes('jsonPath("$.session.sessionId")'));
  assert.ok(source.includes('jsonPath("$.session.mode").is("#{sessionMode}")'));
  assert.ok(source.includes('jsonPath("$.gameId").is("#{gameId}")'));
  assert.ok(source.includes('jsonPath("$.moves[0].from").exists'));
  assert.ok(source.includes('jsonPath("$.game.gameId").is("#{gameId}")'));
  assert.ok(source.includes('jsonPath("$.session.sessionId").is("#{sessionId}")'));
});

test('Gatling documentation covers lecture concepts', () => {
  const docPath = join(process.cwd(), '..', 'performance_workbench.md');
  const doc = readFileSync(docPath, 'utf-8');

  assert.ok(doc.includes('Gatling Open Source'));
  assert.ok(doc.includes('Gatling Enterprise'));
  assert.ok(doc.includes('Code-First Philosophy'));
  assert.ok(doc.includes('Scenario Composition'));
  assert.ok(doc.includes('Feeder Pattern'));
  assert.ok(doc.includes('Gatling Quality Gates'));
  assert.ok(doc.includes('semantic JSON checks'));
  assert.ok(doc.includes('smoke'));
  assert.ok(doc.includes('stress'));
  assert.ok(doc.includes('http://localhost:8080'));
  assert.ok(doc.includes('PerformanceInput'));
});

test('Gatling CLI help communicates load command and observability base URL', () => {
  assert.ok(GATLING_HELP.includes('code-first Gatling Scala simulation'));
  assert.ok(GATLING_HELP.includes('--test <smoke|load|stress>'));
  assert.ok(GATLING_HELP.includes('--gatling-pattern <all|gameplay|session|legalMoves|moveSubmission|readHeavy|writeHeavy>'));
  assert.ok(GATLING_HELP.includes('perf gatling --test smoke --gatling-pattern gameplay --base-url http://localhost:8080'));
  assert.ok(GATLING_HELP.includes('perf gatling --test load --gatling-pattern legalMoves --base-url http://localhost:8080'));
  assert.ok(GATLING_HELP.includes('perf gatling --test stress --gatling-pattern writeHeavy --base-url http://localhost:8080'));
  assert.ok(TOP_LEVEL_HELP.includes('perf gatling --test smoke --gatling-pattern gameplay --base-url http://localhost:8080'));
  assert.ok(TOP_LEVEL_HELP.includes('perf gatling --test load --gatling-pattern legalMoves --base-url http://localhost:8080'));
  assert.ok(TOP_LEVEL_HELP.includes('perf gatling --test stress --gatling-pattern writeHeavy --base-url http://localhost:8080'));
});

test('convertGatlingGlobalStats wraps flat stats in expected envelope', () => {
  const raw = {
    name: 'Global Information',
    numberOfRequests: { total: 500, ok: 490, ko: 10 },
    meanNumberOfRequestsPerSecond: { total: 8.3 },
    percentiles1: { total: 45 },
    percentiles3: { total: 120 },
    percentiles4: { total: 210 },
  };
  const wrapped = convertGatlingGlobalStats(raw);
  assert.deepEqual(wrapped, { stats: raw });
});

test('Gatling normalizer pipeline produces valid PerformanceInput from global_stats shape', () => {
  const raw = {
    numberOfRequests: { total: 500, ok: 490, ko: 10 },
    meanNumberOfRequestsPerSecond: { total: 8.3 },
    percentiles1: { total: 45 },
    percentiles3: { total: 120 },
    percentiles4: { total: 210 },
  };
  const wrapped = convertGatlingGlobalStats(raw) as { stats: typeof raw };
  const context = buildGatlingNormalizerContext(
    { test: 'load', baseUrl: 'http://localhost:10000/api', cpu: 72, memory: 61, phase: 'baseline' },
    getGatlingTestConfig('load'),
  );
  const input = normalizeGatlingSummary(wrapped, context);

  assert.equal(input.metadata.test_type, 'load');
  assert.equal(input.metadata.scenario_name, 'gatling-load-baseline');
  assert.ok(typeof input.latency.p95 === 'number');
  assert.ok(typeof input.errors.error_rate === 'number');
  assert.ok(typeof input.throughput.requests_per_second === 'number');
  assert.ok(typeof input.system.cpu_usage_percent === 'number');
  assert.ok(typeof input.system.memory_usage_percent === 'number');
});

test('interactive Gatling option helper uses log output mode', () => {
  const common = {
    baseUrl: 'http://localhost:10000/api',
    cpu: 72,
    memory: 61,
    phase: 'baseline' as const,
    out: 'docs/performance/baseline',
  };

  const options = buildInteractiveGatlingReportOptions('load', common);
  assert.equal(options.test, 'load');
  assert.equal(options.baseUrl, 'http://localhost:10000/api');
  assert.equal(options.outputMode, 'log');
});

test('Gatling report progress maps execution events to spinner actions', () => {
  const start = gatlingReportSpinnerAction({
    step: 'gatling:start',
    test: 'load',
    message: 'start',
    path: 'docs/performance/baseline/runs/run/logs/gatling_load.log',
  });
  const complete = gatlingReportSpinnerAction({
    step: 'gatling:complete',
    test: 'load',
    message: 'complete',
  });
  const noneAction = gatlingReportSpinnerAction({
    step: 'summary:found',
    test: 'load',
    message: 'summary exported',
    path: 'docs/performance/baseline/gatling_load_summary.json',
  });

  assert.deepEqual(start, {
    kind: 'start',
    text: 'Running Gatling load... writing raw output to log',
    test: 'Gatling load',
    logPath: 'docs/performance/baseline/runs/run/logs/gatling_load.log',
  });
  assert.deepEqual(complete, { kind: 'succeed', text: 'Gatling load execution completed' });
  assert.deepEqual(noneAction, { kind: 'none' });
});

test('Gatling report progress formatter returns success lines for analysis and markdown steps', () => {
  const summaryLine = formatGatlingReportProgressEvent({
    step: 'summary:found',
    test: 'load',
    message: 'summary found',
  });
  const analysisLine = formatGatlingReportProgressEvent({
    step: 'analysis:complete',
    test: 'load',
    message: 'report generated',
  });
  const markdownLine = formatGatlingReportProgressEvent({
    step: 'markdown:written',
    test: 'load',
    message: 'markdown generated',
  });
  const startLine = formatGatlingReportProgressEvent({
    step: 'gatling:start',
    test: 'load',
    message: 'start',
  });

  assert.ok(summaryLine?.includes('summary exported'));
  assert.ok(analysisLine?.includes('deterministic report generated'));
  assert.ok(markdownLine?.includes('Markdown report generated'));
  assert.equal(startLine, undefined);
});

test('findRunHistory detects gatling-single run', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-gatling-history-'));
  createGatlingHistoryRun(outputRoot, 'baseline', '20260505T120000-gatling-load-abcdef', {
    reports: ['gatling_load_report.md'],
  });

  const [item] = findRunHistory(outputRoot);
  assert.equal(item.runId, '20260505T120000-gatling-load-abcdef');
  assert.equal(item.phase, 'baseline');
  assert.equal(item.kind, 'gatling-single');
  assert.equal(item.reports.length, 1);
});

test('buildReviewableRunBundle detects gatling-single run and populates JSON paths', () => {
  const outputRoot = mkdtempSync(join(tmpdir(), 'perf-gatling-bundle-'));
  createGatlingHistoryRun(outputRoot, 'baseline', '20260505T120000-gatling-load-abcdef', {
    reports: ['gatling_load_report.md'],
    jsonReports: {
      'gatling_load_report.json': samplePerformanceReport(),
    },
  });
  const [item] = findRunHistory(outputRoot);
  const bundle = buildReviewableRunBundle(item);

  assert.equal(bundle.tool, 'gatling');
  assert.equal(bundle.kind, 'single');
  assert.equal(bundle.reportJsonPaths.length, 1);
  assert.ok(bundle.reportJsonPaths[0].replace(/\\/g, '/').endsWith('gatling_load_report.json'));
  assert.equal(bundle.reportMarkdownPaths.length, 1);
});

test('renderEnvironmentCheck includes Gatling configured status when gatlingSimulationExists is set', () => {
  const resultConfigured: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'linux',
    cwd: '/home/test',
    configFound: true,
    resolvedArtifactRoot: '/home/test/docs/performance',
    baselineRunsDirExists: false,
    optimizedRunsDirExists: false,
    k6Available: true,
    gatlingSimulationExists: true,
  };
  const outputConfigured = renderEnvironmentCheck(resultConfigured);
  assert.ok(outputConfigured.includes('Gatling:'));
  assert.ok(outputConfigured.includes('configured'));
  assert.ok(outputConfigured.includes('[ok]'));

  const resultMissing: EnvironmentCheckResult = {
    nodeVersion: 'v20.0.0',
    platform: 'linux',
    cwd: '/home/test',
    configFound: false,
    resolvedArtifactRoot: '/home/test/docs/performance',
    baselineRunsDirExists: false,
    optimizedRunsDirExists: false,
    k6Available: false,
    gatlingSimulationExists: false,
  };
  const outputMissing = renderEnvironmentCheck(resultMissing);
  assert.ok(outputMissing.includes('Gatling:'));
  assert.ok(outputMissing.includes('not configured'));
  assert.ok(outputMissing.includes('[warn]'));
});

test('renderEnvironmentCheck omits Gatling row when gatlingSimulationExists is undefined', () => {
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
  const output = renderEnvironmentCheck(result);
  assert.ok(!output.includes('Gatling:'));
});
