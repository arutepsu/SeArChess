import type { PerformanceReport } from '../domain/models';
import {
  buildGatlingArtifactPaths,
  getGatlingTestConfig,
  runGatlingReport,
  type GatlingPhase,
  type GatlingTestName,
  type RunGatlingReportOptions,
} from '../application/runGatlingReport';
import { loadPerformanceConfig } from './config';

export { buildGatlingArtifactPaths, getGatlingTestConfig, type GatlingPhase, type GatlingTestName, type RunGatlingReportOptions };

const GATLING_TESTS = ['smoke', 'load', 'stress'] as const;
const GATLING_PHASES = ['baseline', 'optimized'] as const;

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

function isGatlingTestName(value: string): value is GatlingTestName {
  return GATLING_TESTS.includes(value as GatlingTestName);
}

function isGatlingPhase(value: string): value is GatlingPhase {
  return GATLING_PHASES.includes(value as GatlingPhase);
}

export function parseRunGatlingArgs(args: string[], startDir?: string): RunGatlingReportOptions {
  const config = loadPerformanceConfig(startDir);
  const parsed: Partial<RunGatlingReportOptions> = {
    test: 'load',
    baseUrl: config.baseUrl,
    cpu: config.cpuUsagePercent,
    memory: config.memoryUsagePercent,
    phase: config.defaultPhase,
  };

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    switch (arg) {
      case '--test': {
        const value = requireValue(args, i, '--test');
        if (!isGatlingTestName(value)) {
          throw new Error('--test must be one of: smoke, load, stress');
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
        if (!isGatlingPhase(value)) {
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

  const missing = (['baseUrl', 'cpu', 'memory', 'phase'] as const).filter(
    (key) => parsed[key] === undefined,
  );
  if (missing.length > 0) {
    throw new Error(`Missing required arguments: ${missing.join(', ')}`);
  }

  return parsed as RunGatlingReportOptions;
}

export function formatGatlingReportSummary(report: PerformanceReport, outDir: string): string {
  return [
    `p95 latency: ${report.summary.p95_latency}ms`,
    `error rate: ${(report.summary.error_rate * 100).toFixed(2)}%`,
    `throughput: ${report.summary.throughput} req/s`,
    `bottleneck type: ${report.bottleneck.type}`,
    `diagnosis confidence: ${report.bottleneck.confidence}`,
    `artifact directory: ${outDir}`,
  ].join('\n');
}

export function runGatlingCli(args: string[]): number {
  if (args[0] === '--help' || args[0] === '-h') {
    process.stdout.write(GATLING_HELP + '\n');
    return 0;
  }
  try {
    const options = parseRunGatlingArgs(args);
    const result = runGatlingReport(options);
    process.stdout.write(formatGatlingReportSummary(result.report, result.artifactPaths.outDir) + '\n');
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    return 1;
  }
}

export const GATLING_HELP = [
  'Usage: perf gatling [--test <smoke|load|stress>] [--base-url <url>] [--cpu <number>] [--memory <number>] [--phase <baseline|optimized>] [--out <directory>]',
  '',
  'Runs the code-first Gatling Scala simulation via sbt and produces a deterministic performance report.',
  'Profiles: smoke validates the scenario quickly, load is the normal benchmark, stress explores bottlenecks.',
  'Use --base-url http://localhost:8080 when correlating route traffic with Prometheus/Grafana metrics.',
  'The base URL, output root, default phase, CPU, and memory may come from performance.config.json.',
  'Explicit CLI arguments override config values.',
  '',
  'Options:',
  '  --test      Workload profile: smoke, load, or stress (default: load)',
  '  --base-url  Target base URL',
  '  --cpu       CPU usage % (0–100)',
  '  --memory    Memory usage % (0–100)',
  '  --phase     baseline or optimized (default: from config)',
  '  --out       Output directory override',
  '',
  'Examples:',
  '  perf gatling',
  '  perf gatling --test smoke --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline',
  '  perf gatling --test load --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline',
  '  perf gatling --test stress --base-url http://localhost:8080 --cpu 72 --memory 61 --phase baseline',
  '  perf gatling --test load --base-url http://localhost:10000/api --cpu 72 --memory 61 --phase baseline',
  '  perf gatling --phase optimized --out docs/performance/optimized',
].join('\n');

if (require.main === module) {
  process.exitCode = runGatlingCli(process.argv.slice(2));
}
