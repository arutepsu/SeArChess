import { spawn, spawnSync } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import type { PerformanceReport } from '../domain/models';
import { normalizeGatlingSummary } from '../normalization/gatlingSummaryNormalizer';
import type { NormalizerContext } from '../normalization/normalizerModels';
import { analyze } from './analyzePerformance';
import { renderPerformanceReview } from './renderPerformanceReview';
import { renderPerformanceReviewHtml } from './renderPerformanceReviewHtml';

export type GatlingTestName = 'smoke' | 'load' | 'stress';
export type GatlingPhase = 'baseline' | 'optimized';
export type GatlingOutputMode = 'inherit' | 'log';

export type GatlingProgressStep =
  | 'gatling:start'
  | 'gatling:complete'
  | 'summary:found'
  | 'context:written'
  | 'normalization:complete'
  | 'analysis:complete'
  | 'markdown:written';

export interface GatlingReportProgressEvent {
  step: GatlingProgressStep;
  test: GatlingTestName;
  message: string;
  path?: string;
}

export interface RunGatlingReportOptions {
  test: GatlingTestName;
  baseUrl: string;
  cpu: number;
  memory: number;
  phase: GatlingPhase;
  out?: string;
  outputMode?: GatlingOutputMode;
  onProgress?: (event: GatlingReportProgressEvent) => void;
}

export interface GatlingTestConfig {
  test: GatlingTestName;
  simulationClass: string;
  maxUsers: number;
  duration: string;
  rampUpPattern: string;
}

export interface GatlingArtifactPaths {
  outDir: string;
  summaryPath: string;
  contextPath: string;
  inputPath: string;
  reportJsonPath: string;
  markdownPath: string;
  reportHtmlPath: string;
  logPath: string;
  htmlReportPath?: string;
}

export interface RunGatlingReportResult {
  report: PerformanceReport;
  artifactPaths: GatlingArtifactPaths;
  gatlingExitCode: number | null;
}

function repoRoot(): string {
  return resolve(__dirname, '../../../../..');
}

function gatlingSubprojectDir(): string {
  return join(repoRoot(), 'tools', 'performance', 'gatling');
}

function emitProgress(options: RunGatlingReportOptions, step: GatlingProgressStep, message: string, path?: string): void {
  options.onProgress?.({ step, test: options.test, message, path });
}

export function getGatlingTestConfig(test: GatlingTestName): GatlingTestConfig {
  switch (test) {
    case 'smoke':
      return {
        test,
        simulationClass: 'searchess.SearchessGameplaySimulation',
        maxUsers: 3,
        duration: '3s',
        rampUpPattern: 'linear',
      };
    case 'load':
      return {
        test,
        simulationClass: 'searchess.SearchessGameplaySimulation',
        maxUsers: 50,
        duration: '1m',
        rampUpPattern: 'linear',
      };
    case 'stress':
      return {
        test,
        simulationClass: 'searchess.SearchessGameplaySimulation',
        maxUsers: 100,
        duration: '75s',
        rampUpPattern: 'linear',
      };
  }
}

export function buildGatlingArtifactPaths(test: GatlingTestName, phase: GatlingPhase, out?: string): GatlingArtifactPaths {
  const outDir = out ? resolve(process.cwd(), out) : join(repoRoot(), 'docs', 'performance', phase);
  const prefix = `gatling_${test}`;
  return {
    outDir,
    summaryPath: join(outDir, `${prefix}_summary.json`),
    contextPath: join(outDir, `${prefix}_context.json`),
    inputPath: join(outDir, `${prefix}_input.json`),
    reportJsonPath: join(outDir, `${prefix}_report.json`),
    markdownPath: join(outDir, `${prefix}_report.md`),
    reportHtmlPath: join(outDir, `${prefix}_report.html`),
    logPath: join(outDir, 'logs', `${prefix}.log`),
  };
}

export function buildGatlingNormalizerContext(options: RunGatlingReportOptions, config: GatlingTestConfig): NormalizerContext {
  return {
    testType: options.test,
    scenarioName: `gatling-${options.test}-${options.phase}`,
    timestamp: new Date().toISOString(),
    maxUsers: config.maxUsers,
    duration: config.duration,
    rampUpPattern: config.rampUpPattern,
    cpuUsagePercent: options.cpu,
    memoryUsagePercent: options.memory,
  };
}

export function convertGatlingGlobalStats(globalStats: unknown): unknown {
  return { stats: globalStats };
}

export function findLatestGatlingResultDir(subprojectDir: string): string | undefined {
  const gatlingResultsDir = join(subprojectDir, 'target', 'gatling');
  if (!existsSync(gatlingResultsDir)) return undefined;
  const entries = readdirSync(gatlingResultsDir)
    .map((name) => ({ name, path: join(gatlingResultsDir, name) }))
    .filter(({ path }) => {
      try { return statSync(path).isDirectory(); } catch { return false; }
    })
    .sort((a, b) => b.name.localeCompare(a.name));
  return entries[0]?.path;
}

export function findGatlingHtmlReportPath(resultDir: string): string | undefined {
  const htmlReportPath = join(resultDir, 'index.html');
  return existsSync(htmlReportPath) ? htmlReportPath : undefined;
}

function runIdFromOutputDirectory(out?: string): string {
  return out ? basename(resolve(process.cwd(), out)) || 'local-dev' : 'local-dev';
}

function writeJson(path: string, value: unknown): void {
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n');
}

function buildSbtSpawnArgs(): { cmd: string; args: string[]; useShell: boolean } {
  if (process.platform === 'win32') {
    return {
      cmd: 'sbt "project gatlingPerf" "Gatling/test"',
      args: [],
      useShell: true,
    };
  }
  return {
    cmd: 'sbt',
    args: ['project gatlingPerf', 'Gatling/test'],
    useShell: false,
  };
}

export function buildGatlingProcessEnv(options: RunGatlingReportOptions): NodeJS.ProcessEnv {
  return {
    ...process.env,
    BASE_URL: options.baseUrl,
    GATLING_BASE_URL: options.baseUrl,
    GATLING_RUN_ID: runIdFromOutputDirectory(options.out),
    GATLING_TOOL: 'gatling',
    GATLING_WORKLOAD: options.test,
    GATLING_PHASE: options.phase ?? 'local',
  };
}

function completeGatlingReport(
  options: RunGatlingReportOptions,
  config: GatlingTestConfig,
  artifactPaths: GatlingArtifactPaths,
  gatlingExitCode: number | null,
): RunGatlingReportResult {
  if (gatlingExitCode !== 0) {
    const code = gatlingExitCode ?? 'unknown';
    throw new Error(`Gatling/sbt exited with code ${code} and no summary was produced.`);
  }

  const resultDir = findLatestGatlingResultDir(gatlingSubprojectDir());
  if (!resultDir) {
    throw new Error('Gatling produced no result directory. Check sbt Gatling output for errors.');
  }

  const globalStatsPath = join(resultDir, 'js', 'global_stats.json');
  if (!existsSync(globalStatsPath)) {
    throw new Error(`global_stats.json not found in ${resultDir}. Gatling run may have failed.`);
  }
  artifactPaths.htmlReportPath = findGatlingHtmlReportPath(resultDir);

  const rawStats = JSON.parse(readFileSync(globalStatsPath, 'utf-8'));
  const wrappedStats = convertGatlingGlobalStats(rawStats);
  writeJson(artifactPaths.summaryPath, wrappedStats);
  emitProgress(options, 'summary:found', `Summary exported for Gatling ${options.test}.`, artifactPaths.summaryPath);

  const context = buildGatlingNormalizerContext(options, config);
  writeJson(artifactPaths.contextPath, context);
  emitProgress(options, 'context:written', `Context written for Gatling ${options.test}.`, artifactPaths.contextPath);

  const input = normalizeGatlingSummary(wrappedStats, context);
  writeJson(artifactPaths.inputPath, input);
  emitProgress(options, 'normalization:complete', `Normalized input written for Gatling ${options.test}.`, artifactPaths.inputPath);

  const report = analyze(input);
  writeJson(artifactPaths.reportJsonPath, report);
  emitProgress(options, 'analysis:complete', `Deterministic report generated for Gatling ${options.test}.`, artifactPaths.reportJsonPath);

  const markdown = renderPerformanceReview({
    performanceReport: report,
    title: `Gatling ${options.test} ${options.phase} Performance Report`,
    toolArtifacts: {
      gatlingHtmlReportPath: artifactPaths.htmlReportPath,
    },
  });
  writeFileSync(artifactPaths.markdownPath, markdown);
  emitProgress(options, 'markdown:written', `Markdown report generated for Gatling ${options.test}.`, artifactPaths.markdownPath);

  const html = renderPerformanceReviewHtml({
    performanceReport: report,
    title: `Gatling ${options.test} ${options.phase} Performance Report`,
    toolArtifacts: {
      gatlingHtmlReportPath: artifactPaths.htmlReportPath,
    },
  });
  writeFileSync(artifactPaths.reportHtmlPath, html);

  return { report, artifactPaths, gatlingExitCode };
}

function prepareGatlingRun(options: RunGatlingReportOptions): {
  config: GatlingTestConfig;
  artifactPaths: GatlingArtifactPaths;
  outputMode: GatlingOutputMode;
} {
  const config = getGatlingTestConfig(options.test);
  const artifactPaths = buildGatlingArtifactPaths(options.test, options.phase, options.out);
  mkdirSync(artifactPaths.outDir, { recursive: true });
  return { config, artifactPaths, outputMode: options.outputMode ?? 'inherit' };
}

function runGatlingProcessAsync(
  options: RunGatlingReportOptions,
  outputMode: GatlingOutputMode,
  logFileDescriptor: number | undefined,
): Promise<{ status: number | null; error?: Error }> {
  return new Promise((resolveProcess) => {
    let resolved = false;
    const { cmd, args, useShell } = buildSbtSpawnArgs();
    const stdio: 'inherit' | ['ignore', number, number] =
      outputMode === 'log' && logFileDescriptor !== undefined
        ? ['ignore', logFileDescriptor, logFileDescriptor]
        : 'inherit';
    const child = spawn(cmd, args, {
      shell: useShell,
      stdio,
      cwd: repoRoot(),
      env: buildGatlingProcessEnv(options),
    });
    child.once('error', (error) => {
      if (!resolved) { resolved = true; resolveProcess({ status: null, error }); }
    });
    child.once('close', (code) => {
      if (!resolved) { resolved = true; resolveProcess({ status: code }); }
    });
  });
}

export function runGatlingReport(options: RunGatlingReportOptions): RunGatlingReportResult {
  const { config, artifactPaths, outputMode } = prepareGatlingRun(options);
  let logFileDescriptor: number | undefined;
  try {
    if (outputMode === 'log') {
      mkdirSync(dirname(artifactPaths.logPath), { recursive: true });
      logFileDescriptor = openSync(artifactPaths.logPath, 'w');
    }
    emitProgress(options, 'gatling:start', `Starting Gatling ${options.test}.`, artifactPaths.logPath);
    const { cmd, args, useShell } = buildSbtSpawnArgs();
    const stdio: 'inherit' | ['ignore', number, number] =
      outputMode === 'log' && logFileDescriptor !== undefined
        ? ['ignore', logFileDescriptor, logFileDescriptor]
        : 'inherit';
    const result = spawnSync(cmd, args, {
      shell: useShell,
      stdio,
      encoding: 'utf-8',
      cwd: repoRoot(),
      env: buildGatlingProcessEnv(options),
    });
    emitProgress(options, 'gatling:complete', `Gatling ${options.test} execution completed.`);
    return completeGatlingReport(options, config, artifactPaths, result.status);
  } finally {
    if (logFileDescriptor !== undefined) {
      closeSync(logFileDescriptor);
    }
  }
}

export async function runGatlingReportAsync(options: RunGatlingReportOptions): Promise<RunGatlingReportResult> {
  const { config, artifactPaths, outputMode } = prepareGatlingRun(options);
  let logFileDescriptor: number | undefined;
  try {
    if (outputMode === 'log') {
      mkdirSync(dirname(artifactPaths.logPath), { recursive: true });
      logFileDescriptor = openSync(artifactPaths.logPath, 'w');
    }
    emitProgress(options, 'gatling:start', `Starting Gatling ${options.test}.`, artifactPaths.logPath);
    const result = await runGatlingProcessAsync(options, outputMode, logFileDescriptor);
    emitProgress(options, 'gatling:complete', `Gatling ${options.test} execution completed.`);
    return completeGatlingReport(options, config, artifactPaths, result.status);
  } finally {
    if (logFileDescriptor !== undefined) {
      closeSync(logFileDescriptor);
    }
  }
}
