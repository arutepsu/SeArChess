import type { PerformanceReport } from '../domain/models';
import {
  buildK6ArtifactPaths,
  getK6TestConfig,
  runK6Report,
  shouldContinueAfterK6Failure,
  type K6Phase,
  type K6TestName,
  type RunK6ReportOptions,
} from '../application/runK6Report';
import {
  loadPerformanceConfig,
  resolvePerformanceOutputDir,
  type PerformanceCliConfig,
} from './config';

const K6_TESTS = ['baseline', 'load', 'stress', 'spike'] as const;
const K6_PHASES = ['baseline', 'optimized'] as const;

export {
  buildK6ArtifactPaths,
  getK6TestConfig,
  shouldContinueAfterK6Failure,
  type K6Phase,
  type K6TestName,
  type RunK6ReportOptions as RunK6Options,
};

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

function isK6TestName(value: string): value is K6TestName {
  return K6_TESTS.includes(value as K6TestName);
}

function isK6Phase(value: string): value is K6Phase {
  return K6_PHASES.includes(value as K6Phase);
}

export function parseRunK6Args(
  args: string[],
  config: PerformanceCliConfig = loadPerformanceConfig(),
  configStartDir = process.cwd(),
): RunK6ReportOptions {
  const parsed: Partial<RunK6ReportOptions> = {};

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    switch (arg) {
      case '--test': {
        const value = requireValue(args, i, '--test');
        if (!isK6TestName(value)) {
          throw new Error('--test must be one of baseline, load, stress, spike');
        }
        parsed.test = value;
        i += 1;
        break;
      }
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
  const resolved: Partial<RunK6ReportOptions> = {
    test: parsed.test,
    baseUrl: parsed.baseUrl ?? config.baseUrl,
    cpu: parsed.cpu ?? config.cpuUsagePercent,
    memory: parsed.memory ?? config.memoryUsagePercent,
    phase,
    out: parsed.out ?? (config.outputRoot && phase ? resolvePerformanceOutputDir(config.outputRoot, phase, configStartDir) : undefined),
  };

  const missing = ['test', 'baseUrl', 'cpu', 'memory', 'phase'].filter((key) => resolved[key as keyof RunK6ReportOptions] === undefined);
  if (missing.length > 0) {
    throw new Error(`Missing required arguments: ${missing.join(', ')}`);
  }

  return resolved as RunK6ReportOptions;
}

export function formatK6ReportSummary(report: PerformanceReport, outDir: string): string {
  return [
    `p95 latency: ${report.summary.p95_latency}ms`,
    `error rate: ${(report.summary.error_rate * 100).toFixed(2)}%`,
    `throughput: ${report.summary.throughput} req/s`,
    `bottleneck type: ${report.bottleneck.type}`,
    `confidence: ${report.bottleneck.confidence}`,
    `artifact directory: ${outDir}`,
  ].join('\n');
}

export function runK6Cli(args: string[]): number {
  try {
    const options = parseRunK6Args(args);
    const result = runK6Report(options);
    if (result.continuedAfterThresholdFailure) {
      process.stderr.write(`Warning: k6 exited with code ${result.k6ExitCode}; continuing because summary export exists.\n`);
    }
    process.stdout.write(formatK6ReportSummary(result.report, result.artifactPaths.outDir) + '\n');
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    return 1;
  }
}

if (require.main === module) {
  process.exitCode = runK6Cli(process.argv.slice(2));
}
