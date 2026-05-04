import { runK6Suite, type RunK6SuiteOptions, type RunK6SuiteResult } from '../application/runK6Suite';
import { loadPerformanceConfig, resolvePerformanceOutputDir, type PerformanceCliConfig } from './config';
import { type K6Phase } from '../application/runK6Report';
import { renderTable } from './ui/table';

const K6_PHASES = ['baseline', 'optimized'] as const;

function requireValue(args: string[], index: number, flag: string): string {
  const value = args[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${flag} requires a value`);
  }
  return value;
}

function parsePercent(value: string, flag: string): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100) {
    throw new Error(`${flag} must be a number between 0 and 100`);
  }
  return parsed;
}

function isK6Phase(value: string): value is K6Phase {
  return K6_PHASES.includes(value as K6Phase);
}

export function parseRunK6SuiteArgs(
  args: string[],
  config: PerformanceCliConfig = loadPerformanceConfig(),
  configStartDir = process.cwd(),
): RunK6SuiteOptions {
  const parsed: Partial<RunK6SuiteOptions> = {};

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    switch (arg) {
      case '--base-url':
        parsed.baseUrl = requireValue(args, i, '--base-url');
        i += 1;
        break;
      case '--cpu':
        parsed.cpu = parsePercent(requireValue(args, i, '--cpu'), '--cpu');
        i += 1;
        break;
      case '--memory':
        parsed.memory = parsePercent(requireValue(args, i, '--memory'), '--memory');
        i += 1;
        break;
      case '--phase': {
        const value = requireValue(args, i, '--phase');
        if (!isK6Phase(value)) {
          throw new Error('--phase must be baseline or optimized');
        }
        parsed.phase = value;
        i += 1;
        break;
      }
      case '--out':
        parsed.out = requireValue(args, i, '--out');
        i += 1;
        break;
      default:
        throw new Error(`Unsupported argument: ${arg}`);
    }
  }

  const phase = parsed.phase ?? config.defaultPhase;
  const resolved: Partial<RunK6SuiteOptions> = {
    baseUrl: parsed.baseUrl ?? config.baseUrl,
    cpu: parsed.cpu ?? config.cpuUsagePercent,
    memory: parsed.memory ?? config.memoryUsagePercent,
    phase,
    out: parsed.out ?? (config.outputRoot && phase ? resolvePerformanceOutputDir(config.outputRoot, phase, configStartDir) : undefined),
  };

  const missing = ['baseUrl', 'cpu', 'memory', 'phase'].filter((key) => resolved[key as keyof RunK6SuiteOptions] === undefined);
  if (missing.length > 0) {
    throw new Error(`Missing required arguments: ${missing.join(', ')}`);
  }

  return resolved as RunK6SuiteOptions;
}

function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(2)}%`;
}

function formatLatency(value: number): string {
  return `${value.toFixed(2)}ms`;
}

function formatThroughput(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

export function formatK6SuiteSummary(result: RunK6SuiteResult): string {
  const artifactDirectory = result.results[0]?.artifactPaths.outDir ?? '';
  const table = renderTable(
    ['Test', 'p95', 'Error', 'Throughput', 'Bottleneck', 'Confidence'],
    result.results.map((r) => [
      r.test,
      formatLatency(r.report.summary.p95_latency),
      formatPercent(r.report.summary.error_rate),
      formatThroughput(r.report.summary.throughput),
      r.report.bottleneck.type,
      r.report.bottleneck.confidence,
    ]),
  );
  return [
    'K6 Suite Result',
    '',
    table,
    '',
    'Artifacts:',
    artifactDirectory,
    '',
    'Suite report:',
    result.suiteReportPath,
  ].join('\n');
}

export function runK6SuiteCli(args: string[]): number {
  try {
    const options = parseRunK6SuiteArgs(args);
    const result = runK6Suite(options);
    if (result.results.some((r) => r.continuedAfterThresholdFailure)) {
      process.stderr.write('Warning: one or more k6 tests exited non-zero but summary exports existed.\n');
    }
    process.stdout.write(formatK6SuiteSummary(result) + '\n');
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    return 1;
  }
}
