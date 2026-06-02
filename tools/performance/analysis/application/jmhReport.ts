import { readFileSync, writeFileSync } from 'node:fs';

export interface JmhBenchmarkSummary {
  benchmark: string;
  shortName?: string;
  mode: string;
  score: number;
  scoreError?: number;
  unit: string;
  allocationBytesPerOp?: number;
  params: Record<string, string>;
}

export interface JmhReportSummary {
  benchmarks: JmhBenchmarkSummary[];
}

export interface JmhRunOptionsSummary {
  warmupIterations: number;
  measurementIterations: number;
  forks: number;
  threads: number;
  gcProfiler: boolean;
  pattern: string;
  benchmarkGroupId?: string;
  benchmarkGroupLabel?: string;
}

export interface JmhBenchmarkHighlight {
  benchmark: string;
  shortName: string;
  score: number;
  unit: string;
}

export interface JmhAllocationHighlight {
  benchmark: string;
  shortName: string;
  allocationBytesPerOp: number;
}

export interface JmhStructuredReport {
  tool: 'jmh';
  runId: string;
  phase: 'baseline' | 'optimized';
  createdAt: string;
  benchmarkCount: number;
  options: JmhRunOptionsSummary;
  results: JmhBenchmarkSummary[];
  summary: {
    fastestBenchmark?: JmhBenchmarkHighlight;
    slowestBenchmark?: JmhBenchmarkHighlight;
    allocationDataAvailable: boolean;
    highestAllocationBenchmark?: JmhAllocationHighlight;
  };
  notes: string[];
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function finiteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function parseParams(value: unknown): Record<string, string> {
  if (!isObject(value)) return {};
  const entries = Object.entries(value)
    .filter(([, v]) => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean')
    .map(([k, v]) => [k, String(v)] as const);
  return Object.fromEntries(entries);
}

function parseAllocationBytesPerOp(entry: Record<string, unknown>): number | undefined {
  const secondaryMetrics = entry['secondaryMetrics'];
  if (!isObject(secondaryMetrics)) return undefined;

  const allocationMetric = secondaryMetrics['gc.alloc.rate.norm'];
  if (!isObject(allocationMetric)) return undefined;

  const unit = stringValue(allocationMetric['scoreUnit']);
  if (unit !== 'B/op') return undefined;

  return finiteNumber(allocationMetric['score']);
}

export function shortBenchmarkName(benchmark: string): string {
  const parts = benchmark.split('.');
  if (parts.length < 2) return benchmark;
  return `${parts.at(-2)}.${parts.at(-1)}`;
}

export function parseJmhJson(raw: string): JmhReportSummary {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`Invalid JMH JSON: ${msg}`);
  }

  if (!Array.isArray(parsed)) {
    throw new Error('Invalid JMH JSON: expected an array of benchmark results');
  }

  const benchmarks = parsed.flatMap((entry): JmhBenchmarkSummary[] => {
    if (!isObject(entry)) return [];
    const primaryMetric = entry['primaryMetric'];
    if (!isObject(primaryMetric)) return [];

    const benchmark = stringValue(entry['benchmark']);
    const mode = stringValue(entry['mode']);
    const score = finiteNumber(primaryMetric['score']);
    const unit = stringValue(primaryMetric['scoreUnit']);
    if (!benchmark || !mode || score === undefined || !unit) return [];

    const scoreError = finiteNumber(primaryMetric['scoreError']);
    return [{
      benchmark,
      shortName: shortBenchmarkName(benchmark),
      mode,
      score,
      scoreError,
      unit,
      allocationBytesPerOp: parseAllocationBytesPerOp(entry),
      params: parseParams(entry['params']),
    }];
  });

  return { benchmarks };
}

export function readJmhJson(path: string): JmhReportSummary {
  return parseJmhJson(readFileSync(path, 'utf-8'));
}

function benchmarkHighlight(result: JmhBenchmarkSummary): JmhBenchmarkHighlight {
  return {
    benchmark: result.benchmark,
    shortName: result.shortName ?? shortBenchmarkName(result.benchmark),
    score: result.score,
    unit: result.unit,
  };
}

export function buildJmhStructuredReport(input: {
  summary: JmhReportSummary;
  runId: string;
  phase: 'baseline' | 'optimized';
  options: JmhRunOptionsSummary;
  createdAt?: string;
}): JmhStructuredReport {
  const sortedByScore = [...input.summary.benchmarks].sort((a, b) => a.score - b.score);
  const allocationResults = input.summary.benchmarks
    .filter((result): result is JmhBenchmarkSummary & { allocationBytesPerOp: number } => result.allocationBytesPerOp !== undefined)
    .sort((a, b) => b.allocationBytesPerOp - a.allocationBytesPerOp);
  const highestAllocation = allocationResults[0];

  return {
    tool: 'jmh',
    runId: input.runId,
    phase: input.phase,
    createdAt: input.createdAt ?? new Date().toISOString(),
    benchmarkCount: input.summary.benchmarks.length,
    options: input.options,
    results: input.summary.benchmarks,
    summary: {
      fastestBenchmark: sortedByScore[0] ? benchmarkHighlight(sortedByScore[0]) : undefined,
      slowestBenchmark: sortedByScore.at(-1) ? benchmarkHighlight(sortedByScore.at(-1)!) : undefined,
      allocationDataAvailable: allocationResults.length > 0,
      highestAllocationBenchmark: highestAllocation ? {
        benchmark: highestAllocation.benchmark,
        shortName: highestAllocation.shortName ?? shortBenchmarkName(highestAllocation.benchmark),
        allocationBytesPerOp: highestAllocation.allocationBytesPerOp,
      } : undefined,
    },
    notes: [
      'JMH measures isolated JVM hot paths and is not a normalized Searchess PerformanceReport.',
      'Lower AverageTime scores are faster for the same benchmark and unit.',
      'Allocation data appears only when the JMH GC profiler emits secondary metrics.',
    ],
  };
}

function markdownCell(value: string): string {
  return value.replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

function formatNumber(value: number): string {
  if (Math.abs(value) >= 100) return value.toFixed(2);
  if (Math.abs(value) >= 1) return value.toFixed(3);
  return value.toPrecision(3);
}

function formatParams(params: Record<string, string>): string {
  const entries = Object.entries(params);
  if (entries.length === 0) return '-';
  return entries.map(([key, value]) => `${key}=${value}`).join(', ');
}

export function renderJmhMarkdown(summary: JmhReportSummary, options?: {
  title?: string;
  runId?: string;
  jsonPath?: string;
  textPath?: string;
  benchmarkGroupId?: string;
  benchmarkGroupLabel?: string;
}): string {
  const title = options?.title ?? 'Searchess JMH Benchmark Report';
  const lines = [
    `# ${title}`,
    '',
  ];

  if (options?.runId) lines.push(`Run ID: \`${options.runId}\``);
  if (options?.benchmarkGroupLabel) lines.push(`Benchmark group: \`${options.benchmarkGroupLabel}\``);
  if (options?.runId || options?.benchmarkGroupLabel) lines.push('');

  lines.push(
    'JMH measures isolated JVM hot paths. These results complement k6 and Gatling reports, but they are not normalized `PerformanceReport` results and should not be compared directly with HTTP throughput.',
    '',
    '## Results',
    '',
    '| Benchmark | Mode | Score | Error | Unit | Alloc B/op | Parameters |',
    '|---|---:|---:|---:|---|---:|---|',
  );

  if (summary.benchmarks.length === 0) {
    lines.push('| No benchmark results found | - | - | - | - | - | - |');
  } else {
    for (const result of summary.benchmarks) {
      lines.push([
        markdownCell(result.benchmark),
        markdownCell(result.mode),
        formatNumber(result.score),
        result.scoreError === undefined ? '-' : formatNumber(result.scoreError),
        markdownCell(result.unit),
        result.allocationBytesPerOp === undefined ? '-' : formatNumber(result.allocationBytesPerOp),
        markdownCell(formatParams(result.params)),
      ].join(' | ').replace(/^/, '| ').replace(/$/, ' |'));
    }
  }

  lines.push(
    '',
    '## Interpretation Notes',
    '',
    '- Lower `AverageTime` scores are faster for the same benchmark and unit.',
    '- Score error is JMH measurement uncertainty, not an application failure rate.',
    '- `Alloc B/op` appears only when the JMH GC profiler emits `gc.alloc.rate.norm`.',
    '- Compare JMH runs only when JVM, hardware, benchmark options, and code revision are comparable.',
    '- Use k6 and Gatling for end-to-end service behavior; use JMH for isolated JVM operation cost.',
  );

  if (options?.jsonPath || options?.textPath) {
    lines.push('', '## Artifacts', '');
    if (options.jsonPath) lines.push(`- JMH JSON: \`${options.jsonPath}\``);
    if (options.textPath) lines.push(`- JMH console output: \`${options.textPath}\``);
  }

  return `${lines.join('\n')}\n`;
}

export function renderJmhStructuredMarkdown(report: JmhStructuredReport, artifacts?: {
  rawJsonPath?: string;
  rawTextPath?: string;
  structuredJsonPath?: string;
}): string {
  const lines = [
    '# Searchess JMH Benchmark Report',
    '',
    `Run ID: \`${report.runId}\``,
  ];

  if (report.options.benchmarkGroupLabel) {
    lines.push(`Benchmark group: \`${report.options.benchmarkGroupLabel}\``);
    lines.push(`Pattern: \`${markdownCell(report.options.pattern)}\``);
  }

  lines.push(
    `Phase: \`${report.phase}\``,
    `Created: \`${report.createdAt}\``,
    '',
    'JMH measures isolated JVM hot paths. These results complement k6 and Gatling reports, but they are not normalized `PerformanceReport` results.',
    '',
    '## Summary',
    '',
    `- Benchmarks: ${report.benchmarkCount}`,
    `- Fastest: ${report.summary.fastestBenchmark ? `${report.summary.fastestBenchmark.shortName} (${formatNumber(report.summary.fastestBenchmark.score)} ${report.summary.fastestBenchmark.unit})` : '-'}`,
    `- Slowest: ${report.summary.slowestBenchmark ? `${report.summary.slowestBenchmark.shortName} (${formatNumber(report.summary.slowestBenchmark.score)} ${report.summary.slowestBenchmark.unit})` : '-'}`,
    `- Allocation data: ${report.summary.allocationDataAvailable ? 'yes' : 'no'}`,
  );

  if (report.summary.highestAllocationBenchmark) {
    lines.push(`- Highest allocation: ${report.summary.highestAllocationBenchmark.shortName} (${formatNumber(report.summary.highestAllocationBenchmark.allocationBytesPerOp)} B/op)`);
  }

  lines.push(
    '',
    '## Options',
    '',
    '| Option | Value |',
    '|---|---:|',
    ...(report.options.benchmarkGroupLabel ? [`| Benchmark group | ${markdownCell(report.options.benchmarkGroupLabel)} |`] : []),
    `| Warmup iterations | ${report.options.warmupIterations} |`,
    `| Measurement iterations | ${report.options.measurementIterations} |`,
    `| Forks | ${report.options.forks} |`,
    `| Threads | ${report.options.threads} |`,
    `| GC profiler | ${report.options.gcProfiler ? 'enabled' : 'disabled'} |`,
    `| Pattern | ${markdownCell(report.options.pattern)} |`,
    '',
    '## Results',
    '',
    '| Benchmark | Mode | Score | Error | Unit | Alloc B/op | Parameters |',
    '|---|---:|---:|---:|---|---:|---|',
  );

  if (report.results.length === 0) {
    lines.push('| No benchmark results found | - | - | - | - | - | - |');
  } else {
    for (const result of report.results) {
      lines.push([
        markdownCell(result.shortName ?? result.benchmark),
        markdownCell(result.mode),
        formatNumber(result.score),
        result.scoreError === undefined ? '-' : formatNumber(result.scoreError),
        markdownCell(result.unit),
        result.allocationBytesPerOp === undefined ? '-' : formatNumber(result.allocationBytesPerOp),
        markdownCell(formatParams(result.params)),
      ].join(' | ').replace(/^/, '| ').replace(/$/, ' |'));
    }
  }

  lines.push('', '## Notes', '');
  for (const note of report.notes) {
    lines.push(`- ${note}`);
  }

  if (artifacts?.rawJsonPath || artifacts?.rawTextPath || artifacts?.structuredJsonPath) {
    lines.push('', '## Artifacts', '');
    if (artifacts.structuredJsonPath) lines.push(`- Structured JMH report: \`${artifacts.structuredJsonPath}\``);
    if (artifacts.rawJsonPath) lines.push(`- Raw JMH JSON: \`${artifacts.rawJsonPath}\``);
    if (artifacts.rawTextPath) lines.push(`- JMH console output: \`${artifacts.rawTextPath}\``);
  }

  return `${lines.join('\n')}\n`;
}

export function writeJmhMarkdownReport(inputPath: string, outputPath: string, options?: {
  runId?: string;
  textPath?: string;
  benchmarkGroupId?: string;
  benchmarkGroupLabel?: string;
}): string {
  const summary = readJmhJson(inputPath);
  const markdown = renderJmhMarkdown(summary, {
    runId: options?.runId,
    jsonPath: inputPath,
    textPath: options?.textPath,
    benchmarkGroupId: options?.benchmarkGroupId,
    benchmarkGroupLabel: options?.benchmarkGroupLabel,
  });
  writeFileSync(outputPath, markdown, 'utf-8');
  return markdown;
}
