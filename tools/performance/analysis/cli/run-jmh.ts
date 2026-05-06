import { createWriteStream, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRunId } from '../application/artifacts/runId';
import { resolveArtifactRoot, resolveRepoRoot } from '../application/artifacts/artifactRoot';
import { writeJmhMarkdownReport } from '../application/jmhReport';
import {
  DEFAULT_JMH_GROUP_ID,
  findJmhBenchmarkGroup,
  isValidNamedGroupId,
  listJmhBenchmarkGroups,
} from '../application/jmhBenchmarkGroups';

interface RunJmhOptions {
  runId: string;
  pattern: string;
  warmupIterations: number;
  measurementIterations: number;
  forks: number;
  threads: number;
  gcProfiler: boolean;
  benchmarkGroupId?: string;
  benchmarkGroupLabel?: string;
}

function requireValue(args: string[], index: number, flag: string): string {
  const value = args[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${flag} requires a value`);
  }
  return value;
}

function parsePositiveInteger(value: string, flag: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${flag} must be a positive integer`);
  }
  return parsed;
}

export function parseRunJmhArgs(args: string[]): RunJmhOptions {
  const options: RunJmhOptions = {
    runId: createRunId('jmh'),
    pattern: '',
    warmupIterations: 3,
    measurementIterations: 5,
    forks: 1,
    threads: 1,
    gcProfiler: false,
  };

  let groupSpecified = false;
  let patternSpecified = false;

  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    switch (arg) {
      case '--run-id':
        options.runId = requireValue(args, i, '--run-id');
        i += 1;
        break;
      case '--group': {
        const groupId = requireValue(args, i, '--group');
        if (!isValidNamedGroupId(groupId)) {
          throw new Error(
            `Invalid --group: "${groupId}". Valid groups: ${validNamedGroupIds().join(', ')}`,
          );
        }
        options.benchmarkGroupId = groupId;
        groupSpecified = true;
        i += 1;
        break;
      }
      case '--pattern':
        options.pattern = requireValue(args, i, '--pattern');
        patternSpecified = true;
        i += 1;
        break;
      case '--warmup-iterations':
      case '-wi':
        options.warmupIterations = parsePositiveInteger(requireValue(args, i, arg), arg);
        i += 1;
        break;
      case '--measurement-iterations':
      case '-i':
        options.measurementIterations = parsePositiveInteger(requireValue(args, i, arg), arg);
        i += 1;
        break;
      case '--forks':
      case '-f':
        options.forks = parsePositiveInteger(requireValue(args, i, arg), arg);
        i += 1;
        break;
      case '--threads':
      case '-t':
        options.threads = parsePositiveInteger(requireValue(args, i, arg), arg);
        i += 1;
        break;
      case '--gc-profiler':
        options.gcProfiler = true;
        break;
      default:
        throw new Error(`Unsupported argument: ${arg}`);
    }
  }

  if (groupSpecified && patternSpecified) {
    throw new Error('Use either --group or --pattern, not both.');
  }

  if (groupSpecified && options.benchmarkGroupId) {
    const group = findJmhBenchmarkGroup(options.benchmarkGroupId as Parameters<typeof findJmhBenchmarkGroup>[0]);
    options.pattern = group.pattern!;
    options.benchmarkGroupLabel = group.label;
  } else if (!patternSpecified) {
    const defaultGroup = findJmhBenchmarkGroup(DEFAULT_JMH_GROUP_ID);
    options.benchmarkGroupId = DEFAULT_JMH_GROUP_ID;
    options.pattern = defaultGroup.pattern!;
    options.benchmarkGroupLabel = defaultGroup.label;
  }

  if (!/^[a-zA-Z0-9._=-]+$/.test(options.runId)) {
    throw new Error('--run-id may contain only letters, numbers, dots, underscores, equals signs, and dashes');
  }

  return options;
}

export function buildJmhRunPaths(runId: string): {
  outDir: string;
  jsonPath: string;
  textPath: string;
  markdownPath: string;
} {
  const outDir = join(resolveArtifactRoot(), 'jmh', 'runs', runId);
  return {
    outDir,
    jsonPath: join(outDir, 'jmh-results.json'),
    textPath: join(outDir, 'jmh-results.txt'),
    markdownPath: join(outDir, 'jmh-report.md'),
  };
}

export function buildJmhSbtCommand(options: RunJmhOptions, jsonPath: string): string {
  const normalizedJsonPath = resolve(jsonPath);
  return [
    'benchmarks / Jmh / run',
    `-wi ${options.warmupIterations}`,
    `-i ${options.measurementIterations}`,
    `-f${options.forks}`,
    `-t${options.threads}`,
    '-rf json',
    `-rff ${normalizedJsonPath}`,
    options.gcProfiler ? '-prof gc' : '',
    options.pattern,
  ].filter(Boolean).join(' ');
}

export function runJmhCli(args: string[]): number {
  if (args[0] === '--help' || args[0] === '-h') {
    process.stdout.write(JMH_HELP + '\n');
    return 0;
  }

  try {
    const options = parseRunJmhArgs(args);
    const paths = buildJmhRunPaths(options.runId);
    mkdirSync(paths.outDir, { recursive: true });

    const command = buildJmhSbtCommand(options, paths.jsonPath);
    const log = createWriteStream(paths.textPath, { encoding: 'utf-8' });
    log.write(`$ sbt "${command}"\n\n`);

    const quotedCommand = command.replace(/"/g, '\\"');
    const result = spawnSync(`sbt "${quotedCommand}"`, {
      cwd: resolveRepoRoot(),
      encoding: 'utf-8',
      shell: true,
    });

    if (result.stdout) {
      process.stdout.write(result.stdout);
      log.write(result.stdout);
    }
    if (result.stderr) {
      process.stderr.write(result.stderr);
      log.write(result.stderr);
    }
    log.end();

    if (result.error) throw result.error;
    if (result.status !== 0) {
      throw new Error(`JMH command failed with exit code ${result.status ?? 'unknown'}`);
    }

    writeJmhMarkdownReport(paths.jsonPath, paths.markdownPath, {
      runId: options.runId,
      textPath: paths.textPath,
      benchmarkGroupId: options.benchmarkGroupId,
      benchmarkGroupLabel: options.benchmarkGroupLabel,
    });

    process.stdout.write([
      '',
      'JMH artifacts written:',
      `Folder: ${paths.outDir}`,
      `JSON:   ${paths.jsonPath}`,
      `Text:   ${paths.textPath}`,
      `Report: ${paths.markdownPath}`,
      '',
    ].join('\n'));
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    return 1;
  }
}

export const JMH_HELP = [
  'Usage: npm run jmh:report -- [--run-id <id>] [--group <group-id> | --pattern <jmh-pattern>] [-wi <n>] [-i <n>] [-f <n>] [-t <n>] [--gc-profiler]',
  '',
  'Runs the Searchess JMH suite and writes sidecar artifacts under docs/performance/jmh/runs/<run-id>/.',
  '',
  'Benchmark groups (--group):',
  '  all                   Run the full JMH suite (default)',
  '  domain-rules          Pure chess rule/move-generation cost',
  '  move-application      Pure state transition and move-application cost',
  '  game-service          In-memory application-service boundary cost',
  '  mapping               Domain/application model to DTO mapping cost',
  '  json-rendering        DTO to JSON rendering/serialization cost',
  '  response-construction Mapping plus JSON rendering layer',
  '  postgres-persistence  Relational repository persistence benchmarks',
  '  mongo-persistence     Document repository persistence benchmarks',
  '  persistence           Postgres and Mongo repository persistence benchmarks',
  '',
  'Artifacts:',
  '  jmh-results.json   Raw JMH JSON output',
  '  jmh-results.txt    Captured SBT/JMH console output',
  '  jmh-report.md      Lightweight Markdown summary',
  '',
  'Defaults: --group all -wi 3 -i 5 -f 1 -t 1',
  'Use --gc-profiler to append -prof gc and include allocation metrics when JMH emits them.',
  'Use --pattern <regex> for a custom JMH benchmark pattern (mutually exclusive with --group).',
].join('\n');

function validNamedGroupIds(): string[] {
  return listJmhBenchmarkGroups()
    .filter((group) => group.id !== 'custom')
    .map((group) => group.id);
}

if (require.main === module) {
  process.exitCode = runJmhCli(process.argv.slice(2));
}
