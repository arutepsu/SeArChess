import { existsSync } from 'node:fs';
import { basename, dirname, join, relative } from 'node:path';
import type { K6ArtifactPaths } from '../../application/runK6Report';
import type { GatlingArtifactPaths } from '../../application/runGatlingReport';
import type { JmhArtifactPaths } from '../../application/runJmhReport';
import type { RunK6SuiteResult } from '../../application/runK6Suite';
import type { RunHistoryItem } from '../reports/runHistory';
import type { PerformanceReport } from '../../domain/models';
import type { JmhStructuredReport } from '../../application/jmhReport';
import type { EnvironmentCheckResult } from '../doctor/environmentCheck';
import type { WorkbenchSettingsView } from '../settings/settingsView';
import { HIGH_ERROR_RATE_THRESHOLD, HIGH_LATENCY_THRESHOLD_MS } from '../../domain/thresholds';
import { renderTable } from './table';
import * as theme from './theme';

export interface RunMetadataInput {
  tool: string;
  workload: string;
  group?: string;
  phase: string;
  runId: string;
}

export interface ArtifactSummaryInput {
  folder: string;
  report?: string;
  reportHtml?: string;
  htmlReport?: string;
  reportJson?: string;
  jmhJson?: string;
  jmhOutput?: string;
  log?: string;
  suiteReport?: string;
  suiteReportHtml?: string;
  logs?: string;
}

export type ProgressLineStatus = 'ok' | 'warn' | 'info';

function formatLatency(value: number): string {
  return `${value.toFixed(2)}ms`;
}

function formatPercent(value: number): string {
  return `${(value * 100).toFixed(2)}%`;
}

function formatThroughput(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

function formatJmhNumber(value: number): string {
  if (Math.abs(value) >= 100) return value.toFixed(2);
  if (Math.abs(value) >= 1) return value.toFixed(3);
  return value.toPrecision(3);
}

function metricRows(rows: string[][]): string[][] {
  return rows.map(([metric, value]) => [theme.label(metric), value]);
}

function styleValue(value: string, style: theme.SemanticStyle): string {
  return theme.applySemanticStyle(value, style);
}

export function resultStatusStyle(status: string): theme.SemanticStyle {
  const normalized = status.toLowerCase();
  if (normalized.includes('healthy') || normalized === 'ok' || normalized === 'pass' || normalized === 'passed') {
    return 'success';
  }
  if (normalized.includes('warning') || normalized.includes('degraded') || normalized.includes('pressure') || normalized.includes('suspected')) {
    return 'warning';
  }
  if (normalized.includes('critical') || normalized.includes('failed') || normalized === 'ko' || normalized.includes('error')) {
    return 'error';
  }
  return 'muted';
}

export function resultBottleneckStyle(bottleneck: string): theme.SemanticStyle {
  return bottleneck === 'UNKNOWN' ? 'muted' : 'warning';
}

export function resultConfidenceStyle(confidence: string): theme.SemanticStyle {
  if (confidence === 'HIGH') return 'success';
  if (confidence === 'MEDIUM' || confidence === 'LOW') return 'warning';
  return 'neutral';
}

export function displayBottleneck(report: PerformanceReport): string {
  return humanStatus(report) === 'Healthy' && report.bottleneck.type === 'UNKNOWN'
    ? 'None detected'
    : report.bottleneck.type;
}

export function resultErrorRateStyle(errorRate: number): theme.SemanticStyle {
  if (errorRate === 0) return 'success';
  if (errorRate < 0.01) return 'warning';
  return 'error';
}

export function resultLatencyStyle(p95Latency: number, threshold = HIGH_LATENCY_THRESHOLD_MS): theme.SemanticStyle {
  if (p95Latency > threshold) return 'error';
  if (p95Latency >= threshold * 0.8) return 'warning';
  return 'success';
}

function padLabel(label: string): string {
  return label.padEnd(10, ' ');
}

export function renderWorkbenchHeader(): string {
  return [
    '========================================',
    ' Searchess Performance Workbench',
    '========================================',
  ].join('\n');
}

export function renderRunMetadata(input: RunMetadataInput): string {
  const lines = [
    theme.section('Run'),
    `  ${padLabel('Tool:')} ${input.tool}`,
    `  ${padLabel('Workload:')} ${input.workload}`,
  ];
  if (input.group) {
    lines.push(`  ${padLabel('Group:')} ${input.group}`);
  }
  lines.push(
    `  ${padLabel('Phase:')} ${input.phase}`,
    `  ${padLabel('Run ID:')} ${input.runId}`,
  );
  return lines.join('\n');
}

export function renderProgressLine(status: ProgressLineStatus, text: string): string {
  const prefix = status === 'warn' ? '[warn]' : status === 'ok' ? '[ok]' : '[info]';
  const line = `  ${prefix} ${text}`;

  if (status === 'warn') {
    return theme.warning(line);
  }
  if (status === 'ok') {
    return theme.success(line);
  }
  return theme.muted(line);
}

export function humanStatus(report: PerformanceReport): string {
  if (
    report.bottleneck.type === 'UNKNOWN'
    && report.summary.p95_latency <= HIGH_LATENCY_THRESHOLD_MS
    && report.summary.error_rate <= HIGH_ERROR_RATE_THRESHOLD
  ) {
    return 'Healthy';
  }

  switch (report.bottleneck.type) {
    case 'SCALABILITY':
      return 'Scalability pressure detected';
    case 'CPU_BOUND':
      return 'CPU bottleneck detected';
    case 'IO_BOUND':
      return 'IO/dependency bottleneck suspected';
    case 'CONTENTION':
      return 'Contention/resource saturation suspected';
    case 'UNKNOWN':
      return 'No deterministic bottleneck identified';
  }
}

export function renderSingleRunResult(report: PerformanceReport): string {
  const status = humanStatus(report);
  const p95 = formatLatency(report.summary.p95_latency);
  const errorRate = formatPercent(report.summary.error_rate);
  const throughput = formatThroughput(report.summary.throughput);
  return [
    renderRunCompleteSummary(report),
    '',
    theme.sectionHeader('Result'),
    '',
    renderTable(
      ['Metric', 'Value'],
      metricRows([
        ['Status', styleValue(status, resultStatusStyle(status))],
        ['Bottleneck', styleValue(displayBottleneck(report), resultBottleneckStyle(report.bottleneck.type))],
        ['Diagnosis confidence', styleValue(report.bottleneck.confidence, resultConfidenceStyle(report.bottleneck.confidence))],
        ['p95 latency', styleValue(p95, resultLatencyStyle(report.summary.p95_latency))],
        ['Error rate', styleValue(errorRate, resultErrorRateStyle(report.summary.error_rate))],
        ['Throughput', theme.info(throughput)],
      ]),
    ),
  ].join('\n');
}

export function renderRunCompleteSummary(report: PerformanceReport): string {
  const status = humanStatus(report);
  const p95 = formatLatency(report.summary.p95_latency);
  const errorRate = formatPercent(report.summary.error_rate);
  const throughput = formatThroughput(report.summary.throughput);
  return [
    theme.label('Run complete:'),
    styleValue(status, resultStatusStyle(status)),
    theme.muted('-'),
    `p95 ${styleValue(p95, resultLatencyStyle(report.summary.p95_latency))}`,
    theme.muted('-'),
    `errors ${styleValue(errorRate, resultErrorRateStyle(report.summary.error_rate))}`,
    theme.muted('-'),
    theme.info(throughput),
  ].join(' ');
}

export function renderSuiteRunResult(result: RunK6SuiteResult): string {
  return [
    theme.sectionHeader('Result'),
    '',
    renderTable(
      ['Test', 'p95', 'Error', 'Throughput', 'Bottleneck', 'Diagnosis confidence'],
      result.results.map((entry) => [
        entry.test,
        formatLatency(entry.report.summary.p95_latency),
        formatPercent(entry.report.summary.error_rate),
        formatThroughput(entry.report.summary.throughput),
        entry.report.bottleneck.type,
        entry.report.bottleneck.confidence,
      ]),
    ),
  ].join('\n');
}

export function renderJmhRunResult(report: JmhStructuredReport): string {
  const fastest = report.summary.fastestBenchmark
    ? `${report.summary.fastestBenchmark.shortName} - ${formatJmhNumber(report.summary.fastestBenchmark.score)} ${report.summary.fastestBenchmark.unit}`
    : '-';
  const slowest = report.summary.slowestBenchmark
    ? `${report.summary.slowestBenchmark.shortName} - ${formatJmhNumber(report.summary.slowestBenchmark.score)} ${report.summary.slowestBenchmark.unit}`
    : '-';
  const rows = [
    ['Benchmarks', report.benchmarkCount.toString()],
    ['Mode', report.results[0]?.mode ?? '-'],
    ['Fastest', fastest],
    ['Slowest', slowest],
    ['Allocation data', report.summary.allocationDataAvailable ? theme.success('yes') : theme.muted('no')],
  ];
  if (report.summary.highestAllocationBenchmark) {
    rows.push([
      'Highest allocation',
      `${report.summary.highestAllocationBenchmark.shortName} - ${formatJmhNumber(report.summary.highestAllocationBenchmark.allocationBytesPerOp)} B/op`,
    ]);
  }
  return [
    theme.sectionHeader('Result'),
    '',
    renderTable(['Metric', 'Value'], metricRows(rows)),
  ].join('\n');
}

export function renderJmhToolSummary(report: JmhStructuredReport): string {
  const rows: string[][] = [];
  if (report.options.benchmarkGroupLabel) {
    rows.push(['Benchmark group', report.options.benchmarkGroupLabel]);
  }
  rows.push(
    ['Pattern', report.options.pattern],
    ['Warmup iterations', report.options.warmupIterations.toString()],
    ['Measurement iterations', report.options.measurementIterations.toString()],
    ['Forks', report.options.forks.toString()],
    ['Threads', report.options.threads.toString()],
    ['GC profiler', report.options.gcProfiler ? theme.success('enabled') : theme.muted('disabled')],
  );
  return [
    theme.sectionHeader('Tool Summary'),
    '',
    renderTable(['Metric', 'Value'], metricRows(rows)),
  ].join('\n');
}

export function renderArtifactSummary(input: ArtifactSummaryInput): string {
  const rows: string[][] = [
    [theme.label('Folder'), theme.path(input.folder)],
  ];

  if (input.suiteReport) {
    rows.push([theme.label('Suite report'), theme.path(input.suiteReport)]);
  }
  if (input.suiteReportHtml) {
    rows.push([theme.label('Suite HTML'), theme.path(input.suiteReportHtml)]);
  }
  if (input.report) {
    rows.push([theme.label('Report'), theme.path(input.report)]);
  }
  if (input.reportHtml) {
    rows.push([theme.label('Report HTML'), theme.path(input.reportHtml)]);
  }
  if (input.reportJson) {
    rows.push([theme.label('Report JSON'), theme.path(input.reportJson)]);
  }
  if (input.htmlReport) {
    rows.push([theme.label('Gatling HTML'), theme.path(input.htmlReport)]);
  }
  if (input.jmhJson) {
    rows.push([theme.label('JMH JSON'), theme.path(input.jmhJson)]);
  }
  if (input.jmhOutput) {
    rows.push([theme.label('JMH output'), theme.path(input.jmhOutput)]);
  }
  if (input.logs) {
    rows.push([theme.label('Logs'), theme.path(input.logs)]);
  }
  if (input.log) {
    rows.push([theme.label('Log'), theme.path(input.log)]);
  }

  return [
    theme.sectionHeader('Artifacts'),
    '',
    renderTable(['Artifact', 'Path'], rows),
  ].join('\n');
}

export function blankLineAfterRun(): string {
  return '\n';
}

export function renderObservabilityHint(runId: string, baseUrl: string): string {
  const p = (label: string) => label.padEnd(10, ' ');
  return [
    theme.sectionHeader('Observability'),
    '',
    `  ${p('Run ID:')}${runId}`,
    `  ${p('Target:')}${baseUrl}`,
    `  ${p('Headers:')}X-Performance-Run-Id, X-Performance-Tool, X-Performance-Workload, X-Performance-Phase`,
    `  ${p('Metrics:')}GET /metrics on the backend service`,
    `  ${p('Logs:')}grep backend logs for: performanceRunId=${runId}`,
  ].join('\n');
}

export function artifactSummaryFromK6Paths(paths: K6ArtifactPaths): ArtifactSummaryInput {
  return {
    folder: paths.outDir,
    report: paths.markdownPath,
    reportHtml: paths.reportHtmlPath,
    log: paths.logPath,
  };
}

export function artifactSummaryFromGatlingPaths(paths: GatlingArtifactPaths): ArtifactSummaryInput {
  return {
    folder: paths.outDir,
    report: paths.markdownPath,
    reportHtml: paths.reportHtmlPath,
    htmlReport: paths.htmlReportPath,
    log: paths.logPath,
  };
}

export function artifactSummaryFromJmhPaths(paths: JmhArtifactPaths): ArtifactSummaryInput {
  return {
    folder: paths.outDir,
    report: paths.markdownPath,
    reportJson: paths.reportJsonPath,
    jmhJson: paths.rawJsonPath,
    jmhOutput: paths.rawTextPath,
  };
}

export function artifactSummaryFromK6Suite(result: RunK6SuiteResult, fallbackFolder: string): ArtifactSummaryInput {
  const firstResult = result.results[0];
  return {
    folder: firstResult?.artifactPaths.outDir ?? fallbackFolder,
    suiteReport: result.suiteReportPath,
    suiteReportHtml: result.suiteReportHtmlPath,
    logs: firstResult ? dirname(firstResult.artifactPaths.logPath) : undefined,
  };
}

export function renderRunHistoryList(items: RunHistoryItem[], limit = 10): string {
  const limitedItems = items.slice(0, limit);
  return [
    theme.section('Recent Runs'),
    '',
    renderTable(
      ['Run ID', 'Phase', 'Type', 'Reports'],
      limitedItems.map((item) => [
        item.runId,
        item.phase,
        item.kind,
        item.reports.length.toString(),
      ]),
    ),
  ].join('\n');
}

function relativeRunPath(item: RunHistoryItem, path: string): string {
  const relativePath = relative(item.path, path);
  return relativePath && !relativePath.startsWith('..') ? relativePath : basename(path);
}

function renderFileList(label: string, item: RunHistoryItem, paths: string[]): string[] {
  if (paths.length === 0) {
    return [label, '- none'];
  }

  return [
    label,
    ...paths.map((path) => `- ${relativeRunPath(item, path)}`),
  ];
}

export function renderRunHistoryDetails(item: RunHistoryItem): string {
  return [
    theme.section('Run Details'),
    '',
    `Run ID:  ${item.runId}`,
    `Phase:   ${item.phase}`,
    `Type:    ${item.kind}`,
    `Folder:  ${item.path}`,
    '',
    ...renderFileList('Reports:', item, item.reports),
    '',
    ...renderFileList('HTML Reports:', item, item.htmlReports),
    '',
    ...renderFileList('Logs:', item, item.logs),
  ].join('\n');
}

function jmhArtifactPathsInRun(item: RunHistoryItem): string[] {
  if (item.kind !== 'jmh-single') return [];
  return [
    join(item.path, 'jmh_report.json'),
    join(item.path, 'jmh_results.json'),
    join(item.path, 'jmh_results.txt'),
  ].filter((path) => existsSync(path));
}

export function renderRunHistoryChoiceLabel(item: RunHistoryItem): string {
  const count = item.reports.length;
  const reportLabel = count === 1 ? '1 report' : `${count} reports`;
  return `${item.runId} | ${item.phase} | ${item.kind} | ${reportLabel}`;
}

export function selectPreferredMarkdownReport(item: RunHistoryItem): string | undefined {
  if (item.kind === 'k6-suite') {
    const suiteReport = item.reports.find((report) => report.endsWith('k6_suite_report.md'));
    if (suiteReport) {
      return suiteReport;
    }
  }

  return item.reports[0];
}

export function renderRunArtifactPaths(item: RunHistoryItem): string {
  const lines = [
    theme.section('Artifact Paths'),
    `  Folder:`,
    `    ${item.path}`,
  ];
  if (item.reports.length > 0) {
    lines.push('  Reports:');
    for (const r of item.reports) {
      lines.push(`    ${r}`);
    }
  }
  if (item.htmlReports.length > 0) {
    lines.push('  HTML Reports:');
    for (const r of item.htmlReports) {
      lines.push(`    ${r}`);
    }
  }
  if (item.logs.length > 0) {
    lines.push('  Logs:');
    for (const l of item.logs) {
      lines.push(`    ${l}`);
    }
  }
  const artifacts = jmhArtifactPathsInRun(item);
  if (artifacts.length > 0) {
    lines.push('  Artifacts:');
    for (const artifact of artifacts) {
      lines.push(`    ${artifact}`);
    }
  }
  return lines.join('\n');
}

export function renderMarkdownPreview(
  filePath: string,
  content: string,
  maxLines = 80,
  maxChars = 8000,
): string {
  const charCapped = content.length > maxChars;
  const working = charCapped ? content.slice(0, maxChars) : content;

  const allLines = working.split('\n');
  const lineCapped = allLines.length > maxLines;
  const visibleLines = lineCapped ? allLines.slice(0, maxLines) : allLines;

  let preview = visibleLines.join('\n');
  if (charCapped || lineCapped) {
    preview += '\n... [preview truncated]';
  }

  return [
    theme.section('Markdown Preview'),
    theme.muted(filePath),
    '',
    preview,
  ].join('\n');
}

export function renderStartupContext(result: EnvironmentCheckResult): string {
  const p = (label: string) => label.padEnd(11, ' ');
  const targetValue = result.baseUrl ?? '[warn] not configured';
  return [
    `${p('Config:')}${result.configFound ? 'found' : 'missing'}`,
    `${p('Target:')}${targetValue}`,
    `${p('Artifacts:')}${result.resolvedArtifactRoot}`,
    `${p('k6:')}${result.k6Available ? 'found' : 'missing'}`,
  ].join('\n');
}

export function renderEnvironmentCheck(result: EnvironmentCheckResult): string {
  const p = (label: string) => label.padEnd(16, ' ');
  const okMark = '[ok]';
  const warnMark = '[warn]';

  const lines: string[] = [
    theme.section('Environment Check'),
    '',
    'Runtime',
    `  ${p('Node:')}${result.nodeVersion}`,
    `  ${p('Platform:')}${result.platform}`,
    `  ${p('CWD:')}${result.cwd}`,
    '',
    'Configuration',
    `  ${p('Config:')}${result.configFound ? `found ${okMark}` : `not found ${warnMark}`}`,
  ];

  if (result.configPath) {
    lines.push(`  ${p('File:')}${result.configPath}`);
  }
  if (result.baseUrl) {
    lines.push(`  ${p('Base URL:')}${result.baseUrl}`);
  }
  if (result.outputRoot) {
    lines.push(`  ${p('Output:')}${result.outputRoot}`);
  }

  lines.push(
    '',
    'Artifacts',
    `  ${p('Root:')}${result.resolvedArtifactRoot}`,
    `  ${p('Baseline runs:')}${result.baselineRunsDirExists ? 'found' : 'missing'}`,
    `  ${p('Optimized runs:')}${result.optimizedRunsDirExists ? 'found' : 'missing'}`,
    '',
    'Tools',
    `  ${p('k6:')}${result.k6Available ? `found ${okMark}` : `not found ${warnMark}`}`,
  );

  if (result.k6Version) {
    lines.push(`  ${p('Version:')}${result.k6Version}`);
  }

  if (result.gatlingSimulationExists !== undefined) {
    lines.push(`  ${p('Gatling:')}${result.gatlingSimulationExists ? `configured ${okMark}` : `not configured ${warnMark}`}`);
  }

  if (result.prometheusConfigExists !== undefined || result.grafanaDirExists !== undefined) {
    lines.push(
      '',
      'Observability',
      `  ${p('Prometheus:')}${result.prometheusConfigExists ? `found ${okMark}` : `not found ${warnMark}`}`,
      `  ${p('Grafana:')}${result.grafanaDirExists ? `found ${okMark}` : `not found ${warnMark}`}`,
    );
  }

  return lines.join('\n');
}

export function renderSettingsView(settings: WorkbenchSettingsView): string {
  const p = (label: string) => label.padEnd(19, ' ');
  const missing = 'not configured';
  const configPath = settings.configFilePath ?? settings.configFile;
  const editPath = configPath ?? settings.suggestedConfigFilePath ?? 'not found';
  return [
    theme.section('Settings'),
    '',
    'Config',
    `  ${p('Config file:')}${configPath ?? 'not found'}`,
    `  ${p('Base URL:')}${settings.baseUrl ?? missing}`,
    `  ${p('Output root:')}${settings.outputRoot ?? missing}`,
    `  ${p('Artifact root:')}${settings.artifactRoot}`,
    `  ${p('Default phase:')}${settings.defaultPhase ?? missing}`,
    `  ${p('CPU usage:')}${settings.cpuUsagePercent ?? missing}`,
    `  ${p('Memory usage:')}${settings.memoryUsagePercent ?? missing}`,
    `  ${p('AI enabled:')}${settings.aiEnabled ?? false}`,
    `  ${p('AI provider:')}${settings.aiProvider ?? 'stub'}`,
    `  ${p('AI auto review:')}${settings.aiAutoReview ?? false}`,
    `  ${p('Current directory:')}${settings.cwd}`,
    '',
    'Edit file:',
    `  ${editPath}`,
    '',
    'Example performance.config.json:',
    '',
    '{',
    '  "baseUrl": "http://localhost:10000/api",',
    '  "outputRoot": "docs/performance",',
    '  "defaultPhase": "baseline",',
    '  "cpuUsagePercent": 72,',
    '  "memoryUsagePercent": 61,',
    '  "ai": {',
    '    "enabled": false,',
    '    "provider": "stub",',
    '    "autoReview": false',
    '  }',
    '}',
  ].join('\n');
}
