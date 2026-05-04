import { spawnSync, type SpawnSyncOptions } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { PerformanceReport } from '../domain/models';
import { normalizeK6Summary } from '../normalization/k6SummaryNormalizer';
import type { NormalizerContext } from '../normalization/normalizerModels';
import { analyze } from './analyzePerformance';
import { renderPerformanceReview } from './renderPerformanceReview';

export type K6TestName = 'baseline' | 'load' | 'stress' | 'spike';
export type K6Phase = 'baseline' | 'optimized';

export interface RunK6ReportOptions {
  test: K6TestName;
  baseUrl: string;
  cpu: number;
  memory: number;
  phase: K6Phase;
  out?: string;
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
}

export interface RunK6ReportResult {
  report: PerformanceReport;
  artifactPaths: K6ArtifactPaths;
  k6ExitCode: number | null;
  continuedAfterThresholdFailure: boolean;
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
  };
}

export function shouldContinueAfterK6Failure(k6ExitCode: number | null, summaryExists: boolean): boolean {
  return typeof k6ExitCode === 'number' && k6ExitCode !== 0 && summaryExists;
}

function writeJson(path: string, value: unknown): void {
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n');
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

export function buildK6SpawnOptions(baseUrl: string): SpawnSyncOptions {
  return {
    stdio: 'inherit',
    env: { ...process.env, BASE_URL: baseUrl },
  };
}

export function runK6Report(options: RunK6ReportOptions): RunK6ReportResult {
  const config = getK6TestConfig(options.test);
  const artifactPaths = buildK6ArtifactPaths(options.test, options.phase, options.out);
  mkdirSync(artifactPaths.outDir, { recursive: true });

  const result = spawnSync(
    'k6',
    ['run', config.scriptPath, '--summary-export', artifactPaths.summaryPath],
    buildK6SpawnOptions(options.baseUrl),
  );

  const k6ExitCode = result.status;
  const continuedAfterThresholdFailure = shouldContinueAfterK6Failure(
    k6ExitCode,
    existsSync(artifactPaths.summaryPath),
  );

  if (k6ExitCode !== 0 && !continuedAfterThresholdFailure) {
    const reason = result.error instanceof Error ? ` ${result.error.message}` : '';
    throw new Error(`k6 failed and no summary export was produced.${reason}`);
  }

  const context = buildK6NormalizerContext(options, config);
  writeJson(artifactPaths.contextPath, context);

  const summary = JSON.parse(readFileSync(artifactPaths.summaryPath, 'utf-8'));
  const input = normalizeK6Summary(summary, context);
  writeJson(artifactPaths.inputPath, input);

  const report = analyze(input);
  writeJson(artifactPaths.reportJsonPath, report);

  const markdown = renderPerformanceReview({
    performanceReport: report,
    title: `k6 ${options.test} ${options.phase} Performance Report`,
  });
  writeFileSync(artifactPaths.markdownPath, markdown);

  return {
    report,
    artifactPaths,
    k6ExitCode,
    continuedAfterThresholdFailure,
  };
}
