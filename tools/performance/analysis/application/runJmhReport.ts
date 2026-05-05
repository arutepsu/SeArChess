import { spawn, spawnSync } from 'node:child_process';
import { closeSync, mkdirSync, openSync, readFileSync, writeFileSync, writeSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { createRunId } from './artifacts/runId';
import { resolveRepoRoot } from './artifacts/artifactRoot';
import {
  buildJmhStructuredReport,
  parseJmhJson,
  renderJmhStructuredMarkdown,
  type JmhRunOptionsSummary,
  type JmhStructuredReport,
} from './jmhReport';

export type JmhPhase = 'baseline' | 'optimized';
export type JmhWorkbenchProfile = 'smoke' | 'baseline' | 'gc';
export type JmhOutputMode = 'inherit' | 'log';

export type JmhProgressStep =
  | 'jmh:start'
  | 'jmh:complete'
  | 'raw-json:written'
  | 'structured-report:written'
  | 'markdown:written';

export interface JmhReportProgressEvent {
  step: JmhProgressStep;
  profile: JmhWorkbenchProfile;
  message: string;
  path?: string;
}

export interface RunJmhReportOptions extends JmhRunOptionsSummary {
  profile: JmhWorkbenchProfile;
  phase: JmhPhase;
  runId?: string;
  out?: string;
  outputMode?: JmhOutputMode;
  onProgress?: (event: JmhReportProgressEvent) => void;
}

export interface JmhArtifactPaths {
  outDir: string;
  rawJsonPath: string;
  rawTextPath: string;
  reportJsonPath: string;
  markdownPath: string;
}

export interface RunJmhReportResult {
  report: JmhStructuredReport;
  artifactPaths: JmhArtifactPaths;
  jmhExitCode: number | null;
}

function emitProgress(options: RunJmhReportOptions, step: JmhProgressStep, message: string, path?: string): void {
  options.onProgress?.({ step, profile: options.profile, message, path });
}

function runIdFromOutputDirectory(out?: string): string {
  return out ? basename(resolve(process.cwd(), out)) || 'local-dev' : 'local-dev';
}

export function buildJmhArtifactPaths(phase: JmhPhase, out?: string, runId?: string): JmhArtifactPaths {
  const outDir = out
    ? resolve(process.cwd(), out)
    : join(resolveRepoRoot(), 'docs', 'performance', phase, 'runs', runId ?? createRunId('jmh'));
  return {
    outDir,
    rawJsonPath: join(outDir, 'jmh_results.json'),
    rawTextPath: join(outDir, 'jmh_results.txt'),
    reportJsonPath: join(outDir, 'jmh_report.json'),
    markdownPath: join(outDir, 'jmh_report.md'),
  };
}

export function buildJmhSbtCommand(options: JmhRunOptionsSummary, jsonPath: string): string {
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

export function jmhProfileOptions(profile: JmhWorkbenchProfile, pattern = 'chess.benchmarks.*'): JmhRunOptionsSummary {
  switch (profile) {
    case 'smoke':
      return {
        warmupIterations: 1,
        measurementIterations: 1,
        forks: 1,
        threads: 1,
        gcProfiler: false,
        pattern,
      };
    case 'baseline':
      return {
        warmupIterations: 3,
        measurementIterations: 5,
        forks: 1,
        threads: 1,
        gcProfiler: false,
        pattern,
      };
    case 'gc':
      return {
        warmupIterations: 3,
        measurementIterations: 5,
        forks: 1,
        threads: 1,
        gcProfiler: true,
        pattern,
      };
  }
}

function completeJmhReport(
  options: RunJmhReportOptions,
  artifactPaths: JmhArtifactPaths,
  jmhExitCode: number | null,
): RunJmhReportResult {
  if (jmhExitCode !== 0) {
    const code = jmhExitCode ?? 'unknown';
    throw new Error(`JMH/sbt exited with code ${code}. See ${artifactPaths.rawTextPath}`);
  }

  emitProgress(options, 'raw-json:written', 'Raw JMH JSON written.', artifactPaths.rawJsonPath);
  const summary = parseJmhJson(readFileSync(artifactPaths.rawJsonPath, 'utf-8'));
  const report = buildJmhStructuredReport({
    summary,
    runId: options.runId ?? runIdFromOutputDirectory(options.out),
    phase: options.phase,
    options: {
      warmupIterations: options.warmupIterations,
      measurementIterations: options.measurementIterations,
      forks: options.forks,
      threads: options.threads,
      gcProfiler: options.gcProfiler,
      pattern: options.pattern,
      benchmarkGroupId: options.benchmarkGroupId,
      benchmarkGroupLabel: options.benchmarkGroupLabel,
    },
  });

  writeFileSync(artifactPaths.reportJsonPath, JSON.stringify(report, null, 2) + '\n', 'utf-8');
  emitProgress(options, 'structured-report:written', 'Structured JMH report written.', artifactPaths.reportJsonPath);

  const markdown = renderJmhStructuredMarkdown(report, {
    rawJsonPath: artifactPaths.rawJsonPath,
    rawTextPath: artifactPaths.rawTextPath,
    structuredJsonPath: artifactPaths.reportJsonPath,
  });
  writeFileSync(artifactPaths.markdownPath, markdown, 'utf-8');
  emitProgress(options, 'markdown:written', 'Markdown report written.', artifactPaths.markdownPath);

  return { report, artifactPaths, jmhExitCode };
}

function prepareJmhRun(options: RunJmhReportOptions): JmhArtifactPaths {
  const artifactPaths = buildJmhArtifactPaths(options.phase, options.out, options.runId);
  mkdirSync(dirname(artifactPaths.rawTextPath), { recursive: true });
  mkdirSync(artifactPaths.outDir, { recursive: true });
  return artifactPaths;
}

function quotedSbtCommand(command: string): string {
  return `sbt "${command.replace(/"/g, '\\"')}"`;
}

function runJmhProcessAsync(
  command: string,
  outputMode: JmhOutputMode,
  logFileDescriptor: number | undefined,
): Promise<{ status: number | null; error?: Error }> {
  return new Promise((resolveProcess) => {
    let resolved = false;
    const stdio: 'inherit' | ['ignore', number, number] =
      outputMode === 'log' && logFileDescriptor !== undefined
        ? ['ignore', logFileDescriptor, logFileDescriptor]
        : 'inherit';
    const child = spawn(quotedSbtCommand(command), {
      shell: true,
      stdio,
      cwd: resolveRepoRoot(),
    });
    child.once('error', (error) => {
      if (!resolved) { resolved = true; resolveProcess({ status: null, error }); }
    });
    child.once('close', (code) => {
      if (!resolved) { resolved = true; resolveProcess({ status: code }); }
    });
  });
}

export function runJmhReport(options: RunJmhReportOptions): RunJmhReportResult {
  const artifactPaths = prepareJmhRun(options);
  const command = buildJmhSbtCommand(options, artifactPaths.rawJsonPath);
  let logFileDescriptor: number | undefined;
  try {
    if (options.outputMode === 'log') {
      logFileDescriptor = openSync(artifactPaths.rawTextPath, 'w');
      writeSync(logFileDescriptor, `$ ${quotedSbtCommand(command)}\n\n`);
    }
    emitProgress(options, 'jmh:start', 'Starting JMH benchmark.', artifactPaths.rawTextPath);
    const result = spawnSync(quotedSbtCommand(command), {
      shell: true,
      stdio: options.outputMode === 'log' && logFileDescriptor !== undefined
        ? ['ignore', logFileDescriptor, logFileDescriptor]
        : 'inherit',
      cwd: resolveRepoRoot(),
    });
    emitProgress(options, 'jmh:complete', 'JMH execution completed.');
    return completeJmhReport(options, artifactPaths, result.status);
  } finally {
    if (logFileDescriptor !== undefined) {
      closeSync(logFileDescriptor);
    }
  }
}

export async function runJmhReportAsync(options: RunJmhReportOptions): Promise<RunJmhReportResult> {
  const artifactPaths = prepareJmhRun(options);
  const command = buildJmhSbtCommand(options, artifactPaths.rawJsonPath);
  let logFileDescriptor: number | undefined;
  try {
    if (options.outputMode === 'log') {
      logFileDescriptor = openSync(artifactPaths.rawTextPath, 'w');
      writeSync(logFileDescriptor, `$ ${quotedSbtCommand(command)}\n\n`);
    }
    emitProgress(options, 'jmh:start', 'Starting JMH benchmark.', artifactPaths.rawTextPath);
    const result = await runJmhProcessAsync(command, options.outputMode ?? 'inherit', logFileDescriptor);
    emitProgress(options, 'jmh:complete', 'JMH execution completed.');
    return completeJmhReport(options, artifactPaths, result.status);
  } finally {
    if (logFileDescriptor !== undefined) {
      closeSync(logFileDescriptor);
    }
  }
}
