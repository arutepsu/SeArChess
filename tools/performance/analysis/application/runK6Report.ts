import { spawn, spawnSync, type SpawnOptions, type SpawnSyncOptions } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import type { PerformanceReport } from '../domain/models';
import { normalizeK6Summary } from '../normalization/k6SummaryNormalizer';
import type { NormalizerContext } from '../normalization/normalizerModels';
import { analyze } from './analyzePerformance';
import { renderPerformanceReview } from './renderPerformanceReview';

export type K6TestName = 'baseline' | 'load' | 'stress' | 'spike';
export type K6Phase = 'baseline' | 'optimized';
export type K6OutputMode = 'inherit' | 'log';
export type K6ReportProgressStep =
  | 'k6:start'
  | 'k6:complete'
  | 'k6:threshold-warning'
  | 'summary:found'
  | 'context:written'
  | 'normalization:complete'
  | 'analysis:complete'
  | 'markdown:written';

export interface K6ReportProgressEvent {
  step: K6ReportProgressStep;
  test: K6TestName;
  message: string;
  path?: string;
}

export interface RunK6ReportOptions {
  test: K6TestName;
  baseUrl: string;
  cpu: number;
  memory: number;
  phase: K6Phase;
  out?: string;
  outputMode?: K6OutputMode;
  onProgress?: (event: K6ReportProgressEvent) => void;
}

export interface K6TestConfig {
  test: K6TestName;
  scriptPath: string;
  maxUsers: number;
  duration: string;
  rampUpPattern: string;
}

export interface K6ArtifactPaths {
  outDir: string;
  summaryPath: string;
  contextPath: string;
  inputPath: string;
  reportJsonPath: string;
  markdownPath: string;
  logPath: string;
}

export interface RunK6ReportResult {
  report: PerformanceReport;
  artifactPaths: K6ArtifactPaths;
  k6ExitCode: number | null;
  continuedAfterThresholdFailure: boolean;
}

interface K6ProcessResult {
  status: number | null;
  error?: Error;
}

function repoRoot(): string {
  return resolve(__dirname, '../../../../..');
}

function performanceDir(): string {
  return resolve(__dirname, '../../..');
}

export function getK6TestConfig(test: K6TestName): K6TestConfig {
  const baseDir = join(performanceDir(), 'k6');
  switch (test) {
    case 'baseline':
      return {
        test,
        scriptPath: join(baseDir, 'baseline_test.js'),
        maxUsers: 10,
        duration: '26s',
        rampUpPattern: 'per-vu-iterations',
      };
    case 'load':
      return {
        test,
        scriptPath: join(baseDir, 'load_test.js'),
        maxUsers: 50,
        duration: '1m',
        rampUpPattern: 'constant-vus',
      };
    case 'stress':
      return {
        test,
        scriptPath: join(baseDir, 'stress_test.js'),
        maxUsers: 1000,
        duration: '3m',
        rampUpPattern: 'staged-ramp',
      };
    case 'spike':
      return {
        test,
        scriptPath: join(baseDir, 'spike_test.js'),
        maxUsers: 150,
        duration: '1m',
        rampUpPattern: 'spike',
      };
  }
}

export function buildK6ArtifactPaths(test: K6TestName, phase: K6Phase, out?: string): K6ArtifactPaths {
  const outDir = out ? resolve(process.cwd(), out) : join(repoRoot(), 'docs', 'performance', phase);
  const prefix = `k6_${test}`;
  return {
    outDir,
    summaryPath: join(outDir, `${prefix}_summary.json`),
    contextPath: join(outDir, `${prefix}_context.json`),
    inputPath: join(outDir, `${prefix}_input.json`),
    reportJsonPath: join(outDir, `${prefix}_report.json`),
    markdownPath: join(outDir, `${prefix}_report.md`),
    logPath: join(outDir, 'logs', `${prefix}.log`),
  };
}

export function shouldContinueAfterK6Failure(k6ExitCode: number | null, summaryExists: boolean): boolean {
  return typeof k6ExitCode === 'number' && k6ExitCode !== 0 && summaryExists;
}

function writeJson(path: string, value: unknown): void {
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n');
}

function emitProgress(
  options: RunK6ReportOptions,
  step: K6ReportProgressStep,
  message: string,
  path?: string,
): void {
  options.onProgress?.({
    step,
    test: options.test,
    message,
    path,
  });
}

export function buildK6NormalizerContext(options: RunK6ReportOptions, config: K6TestConfig): NormalizerContext {
  return {
    testType: options.test,
    scenarioName: `k6-${options.test}-${options.phase}`,
    timestamp: new Date().toISOString(),
    maxUsers: config.maxUsers,
    duration: config.duration,
    rampUpPattern: config.rampUpPattern,
    cpuUsagePercent: options.cpu,
    memoryUsagePercent: options.memory,
  };
}

export function buildK6SpawnOptions(
  baseUrl: string,
  outputMode: K6OutputMode = 'inherit',
  logFileDescriptor?: number,
): SpawnSyncOptions & SpawnOptions {
  if (outputMode === 'log' && logFileDescriptor === undefined) {
    throw new Error('logFileDescriptor is required when k6 outputMode is log');
  }
  return {
    stdio: outputMode === 'log' ? ['ignore', logFileDescriptor, logFileDescriptor] : 'inherit',
    env: { ...process.env, BASE_URL: baseUrl },
  };
}

function completeK6Report(
  options: RunK6ReportOptions,
  config: K6TestConfig,
  artifactPaths: K6ArtifactPaths,
  processResult: K6ProcessResult,
): RunK6ReportResult {
  const k6ExitCode = processResult.status;
  const continuedAfterThresholdFailure = shouldContinueAfterK6Failure(
    k6ExitCode,
    existsSync(artifactPaths.summaryPath),
  );
  if (continuedAfterThresholdFailure) {
    emitProgress(options, 'k6:threshold-warning', `k6 ${options.test} exited non-zero; continuing because summary export exists.`, artifactPaths.summaryPath);
  }

  if (k6ExitCode !== 0 && !continuedAfterThresholdFailure) {
    const reason = processResult.error instanceof Error ? ` ${processResult.error.message}` : '';
    throw new Error(`k6 failed and no summary export was produced.${reason}`);
  }

  emitProgress(options, 'summary:found', `Summary export found for k6 ${options.test}.`, artifactPaths.summaryPath);
  const context = buildK6NormalizerContext(options, config);
  writeJson(artifactPaths.contextPath, context);
  emitProgress(options, 'context:written', `Context written for k6 ${options.test}.`, artifactPaths.contextPath);

  const summary = JSON.parse(readFileSync(artifactPaths.summaryPath, 'utf-8'));
  const input = normalizeK6Summary(summary, context);
  writeJson(artifactPaths.inputPath, input);
  emitProgress(options, 'normalization:complete', `Normalized input written for k6 ${options.test}.`, artifactPaths.inputPath);

  const report = analyze(input);
  writeJson(artifactPaths.reportJsonPath, report);
  emitProgress(options, 'analysis:complete', `Deterministic report generated for k6 ${options.test}.`, artifactPaths.reportJsonPath);

  const markdown = renderPerformanceReview({
    performanceReport: report,
    title: `k6 ${options.test} ${options.phase} Performance Report`,
  });
  writeFileSync(artifactPaths.markdownPath, markdown);
  emitProgress(options, 'markdown:written', `Markdown report generated for k6 ${options.test}.`, artifactPaths.markdownPath);

  return {
    report,
    artifactPaths,
    k6ExitCode,
    continuedAfterThresholdFailure,
  };
}

function prepareK6Run(options: RunK6ReportOptions): { config: K6TestConfig; artifactPaths: K6ArtifactPaths; outputMode: K6OutputMode } {
  const config = getK6TestConfig(options.test);
  const artifactPaths = buildK6ArtifactPaths(options.test, options.phase, options.out);
  mkdirSync(artifactPaths.outDir, { recursive: true });
  return {
    config,
    artifactPaths,
    outputMode: options.outputMode ?? 'inherit',
  };
}

function runK6ProcessAsync(
  options: RunK6ReportOptions,
  config: K6TestConfig,
  artifactPaths: K6ArtifactPaths,
  outputMode: K6OutputMode,
  logFileDescriptor?: number,
): Promise<K6ProcessResult> {
  return new Promise((resolveProcess) => {
    let resolved = false;
    const child = spawn(
      'k6',
      ['run', config.scriptPath, '--summary-export', artifactPaths.summaryPath],
      buildK6SpawnOptions(options.baseUrl, outputMode, logFileDescriptor),
    );

    child.once('error', (error) => {
      if (!resolved) {
        resolved = true;
        resolveProcess({ status: null, error });
      }
    });
    child.once('close', (code) => {
      if (!resolved) {
        resolved = true;
        resolveProcess({ status: code });
      }
    });
  });
}

export function runK6Report(options: RunK6ReportOptions): RunK6ReportResult {
  const { config, artifactPaths, outputMode } = prepareK6Run(options);
  let logFileDescriptor: number | undefined;

  try {
    if (outputMode === 'log') {
      mkdirSync(dirname(artifactPaths.logPath), { recursive: true });
      logFileDescriptor = openSync(artifactPaths.logPath, 'w');
    }

    emitProgress(options, 'k6:start', `Starting k6 ${options.test}.`, artifactPaths.logPath);
    const result = spawnSync(
      'k6',
      ['run', config.scriptPath, '--summary-export', artifactPaths.summaryPath],
      buildK6SpawnOptions(options.baseUrl, outputMode, logFileDescriptor),
    );
    emitProgress(options, 'k6:complete', `k6 ${options.test} execution completed.`);

    return completeK6Report(options, config, artifactPaths, {
      status: result.status,
      error: result.error,
    });
  } finally {
    if (logFileDescriptor !== undefined) {
      closeSync(logFileDescriptor);
    }
  }
}

export async function runK6ReportAsync(options: RunK6ReportOptions): Promise<RunK6ReportResult> {
  const { config, artifactPaths, outputMode } = prepareK6Run(options);
  let logFileDescriptor: number | undefined;

  try {
    if (outputMode === 'log') {
      mkdirSync(dirname(artifactPaths.logPath), { recursive: true });
      logFileDescriptor = openSync(artifactPaths.logPath, 'w');
    }

    emitProgress(options, 'k6:start', `Starting k6 ${options.test}.`, artifactPaths.logPath);
    const result = await runK6ProcessAsync(options, config, artifactPaths, outputMode, logFileDescriptor);
    emitProgress(options, 'k6:complete', `k6 ${options.test} execution completed.`);

    return completeK6Report(options, config, artifactPaths, result);
  } finally {
    if (logFileDescriptor !== undefined) {
      closeSync(logFileDescriptor);
    }
  }
}
