import { basename, dirname, relative } from 'node:path';
import type { K6ArtifactPaths } from '../../application/runK6Report';
import type { RunK6SuiteResult } from '../../application/runK6Suite';
import type { RunHistoryItem } from '../reports/runHistory';
import type { PerformanceReport } from '../../domain/models';
import type { EnvironmentCheckResult } from '../doctor/environmentCheck';
import type { WorkbenchSettingsView } from '../settings/settingsView';
import { HIGH_ERROR_RATE_THRESHOLD, HIGH_LATENCY_THRESHOLD_MS } from '../../domain/thresholds';
import { renderTable } from './table';
import * as theme from './theme';

export interface RunMetadataInput {
  tool: string;
  workload: string;
  phase: string;
  runId: string;
}

export interface ArtifactSummaryInput {
  folder: string;
  report?: string;
  log?: string;
  suiteReport?: string;
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
  return [
    theme.section('Run'),
    `  ${padLabel('Tool:')} ${input.tool}`,
    `  ${padLabel('Workload:')} ${input.workload}`,
    `  ${padLabel('Phase:')} ${input.phase}`,
    `  ${padLabel('Run ID:')} ${input.runId}`,
  ].join('\n');
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
  return [
    theme.section('Result'),
    '',
    renderTable(
      ['Metric', 'Value'],
      [
        ['Status', humanStatus(report)],
        ['Bottleneck', report.bottleneck.type],
        ['Confidence', report.bottleneck.confidence],
        ['p95 latency', formatLatency(report.summary.p95_latency)],
        ['Error rate', formatPercent(report.summary.error_rate)],
        ['Throughput', formatThroughput(report.summary.throughput)],
      ],
    ),
  ].join('\n');
}

export function renderSuiteRunResult(result: RunK6SuiteResult): string {
  return [
    theme.section('Result'),
    '',
    renderTable(
      ['Test', 'p95', 'Error', 'Throughput', 'Bottleneck', 'Confidence'],
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

export function renderArtifactSummary(input: ArtifactSummaryInput): string {
  const lines = [
    theme.section('Artifacts'),
    `  ${padLabel('Folder:')} ${theme.muted(input.folder)}`,
  ];

  if (input.suiteReport) {
    lines.push(`  ${padLabel('Suite report:')} ${theme.muted(input.suiteReport)}`);
  }
  if (input.report) {
    lines.push(`  ${padLabel('Report:')} ${theme.muted(input.report)}`);
  }
  if (input.logs) {
    lines.push(`  ${padLabel('Logs:')} ${theme.muted(input.logs)}`);
  }
  if (input.log) {
    lines.push(`  ${padLabel('Log:')} ${theme.muted(input.log)}`);
  }

  return lines.join('\n');
}

export function artifactSummaryFromK6Paths(paths: K6ArtifactPaths): ArtifactSummaryInput {
  return {
    folder: paths.outDir,
    report: paths.markdownPath,
    log: paths.logPath,
  };
}

export function artifactSummaryFromK6Suite(result: RunK6SuiteResult, fallbackFolder: string): ArtifactSummaryInput {
  const firstResult = result.results[0];
  return {
    folder: firstResult?.artifactPaths.outDir ?? fallbackFolder,
    suiteReport: result.suiteReportPath,
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
    ...renderFileList('Logs:', item, item.logs),
  ].join('\n');
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
  if (item.logs.length > 0) {
    lines.push('  Logs:');
    for (const l of item.logs) {
      lines.push(`    ${l}`);
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
    '  "memoryUsagePercent": 61',
    '}',
  ].join('\n');
}
