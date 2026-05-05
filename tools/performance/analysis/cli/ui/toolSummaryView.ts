import { readFileSync } from 'node:fs';
import { renderTable } from './table';
import * as theme from './theme';

export type ToolSummaryTool = 'k6' | 'gatling';

export interface ToolSummaryRow {
  metric: string;
  value: string;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function numberAt(root: unknown, path: string): number | undefined {
  const parts = path.split('.');
  let current: unknown = root;
  for (const part of parts) {
    if (!isObject(current)) return undefined;
    current = current[part];
  }
  return typeof current === 'number' && Number.isFinite(current) ? current : undefined;
}

function firstNumber(root: unknown, paths: string[]): number | undefined {
  for (const path of paths) {
    const value = numberAt(root, path);
    if (value !== undefined) return value;
  }
  return undefined;
}

function formatCount(value: number): string {
  return Math.round(value).toString();
}

function formatMs(value: number): string {
  return `${value.toFixed(2)}ms`;
}

function formatRate(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

function formatPercent(value: number): string {
  return `${(value * 100).toFixed(2)}%`;
}

function pushRow(rows: ToolSummaryRow[], metric: string, value: string | undefined): void {
  if (value && !value.includes('undefined') && !value.includes('NaN')) {
    rows.push({ metric, value });
  }
}

function distributionPercent(count: number | undefined, total: number | undefined, directPercent: number | undefined): number | undefined {
  if (directPercent !== undefined) {
    return directPercent > 1 ? directPercent / 100 : directPercent;
  }
  if (count !== undefined && total !== undefined && total > 0) {
    return count / total;
  }
  return undefined;
}

export function buildGatlingToolSummaryRows(summary: unknown, htmlReportPath?: string): ToolSummaryRow[] {
  const total = numberAt(summary, 'stats.numberOfRequests.total');
  const ok = numberAt(summary, 'stats.numberOfRequests.ok');
  const ko = numberAt(summary, 'stats.numberOfRequests.ko');
  const rows: ToolSummaryRow[] = [];

  if (total !== undefined && ok !== undefined && ko !== undefined) {
    pushRow(rows, 'Requests', `${formatCount(total)} total / ${formatCount(ok)} OK / ${formatCount(ko)} KO`);
  }

  const min = numberAt(summary, 'stats.minResponseTime.total');
  const mean = numberAt(summary, 'stats.meanResponseTime.total');
  const max = numberAt(summary, 'stats.maxResponseTime.total');
  if (min !== undefined && mean !== undefined && max !== undefined) {
    pushRow(rows, 'Latency', `min ${formatMs(min)} / mean ${formatMs(mean)} / max ${formatMs(max)}`);
  }

  const p50 = numberAt(summary, 'stats.percentiles1.total');
  const p75 = numberAt(summary, 'stats.percentiles2.total');
  const p95 = numberAt(summary, 'stats.percentiles3.total');
  const p99 = numberAt(summary, 'stats.percentiles4.total');
  if (p50 !== undefined && p75 !== undefined && p95 !== undefined && p99 !== undefined) {
    pushRow(rows, 'Percentiles', `p50 ${formatMs(p50)} / p75 ${formatMs(p75)} / p95 ${formatMs(p95)} / p99 ${formatMs(p99)}`);
  }

  const requestRate = numberAt(summary, 'stats.meanNumberOfRequestsPerSecond.total');
  if (requestRate !== undefined) {
    pushRow(rows, 'Request rate', formatRate(requestRate));
  }

  const group1 = distributionPercent(numberAt(summary, 'stats.group1.count'), total, numberAt(summary, 'stats.group1.percentage'));
  const group2 = distributionPercent(numberAt(summary, 'stats.group2.count'), total, numberAt(summary, 'stats.group2.percentage'));
  const group3 = distributionPercent(numberAt(summary, 'stats.group3.count'), total, numberAt(summary, 'stats.group3.percentage'));
  const group4 = distributionPercent(numberAt(summary, 'stats.group4.count'), total, numberAt(summary, 'stats.group4.percentage'));
  if (group1 !== undefined && group2 !== undefined && group3 !== undefined && group4 !== undefined) {
    pushRow(
      rows,
      'Distribution',
      `<800ms ${formatPercent(group1)} / 800-1200ms ${formatPercent(group2)} / >=1200ms ${formatPercent(group3)} / failed ${formatPercent(group4)}`,
    );
  }

  if (htmlReportPath) {
    pushRow(rows, 'Native report', htmlReportPath);
  }

  return rows;
}

export function buildK6ToolSummaryRows(summary: unknown): ToolSummaryRow[] {
  const rows: ToolSummaryRow[] = [];
  const requestCount = firstNumber(summary, ['metrics.http_reqs.count', 'metrics.http_reqs.values.count']);
  const requestRate = firstNumber(summary, ['metrics.http_reqs.rate', 'metrics.http_reqs.values.rate']);
  if (requestCount !== undefined && requestRate !== undefined) {
    pushRow(rows, 'Requests', `${formatCount(requestCount)} total / ${formatRate(requestRate)}`);
  } else if (requestCount !== undefined) {
    pushRow(rows, 'Requests', `${formatCount(requestCount)} total`);
  } else if (requestRate !== undefined) {
    pushRow(rows, 'Requests', formatRate(requestRate));
  }

  const checkRate = firstNumber(summary, ['metrics.checks.value', 'metrics.checks.rate', 'metrics.checks.values.rate']);
  const checkPasses = firstNumber(summary, ['metrics.checks.passes', 'metrics.checks.values.passes']);
  const checkFails = firstNumber(summary, ['metrics.checks.fails', 'metrics.checks.values.fails']);
  if (checkRate !== undefined) {
    const counts = checkPasses !== undefined && checkFails !== undefined
      ? ` (${formatCount(checkPasses)} passed / ${formatCount(checkFails)} failed)`
      : '';
    pushRow(rows, 'Checks', `${formatPercent(checkRate)} passed${counts}`);
  }

  const iterations = firstNumber(summary, ['metrics.iterations.count', 'metrics.iterations.values.count']);
  if (iterations !== undefined) {
    pushRow(rows, 'Iterations', formatCount(iterations));
  }

  const p50 = firstNumber(summary, ['metrics.http_req_duration.med', 'metrics.http_req_duration.values.p(50)']);
  const p95 = firstNumber(summary, ['metrics.http_req_duration.p(95)', 'metrics.http_req_duration.values.p(95)']);
  const p99 = firstNumber(summary, ['metrics.http_req_duration.p(99)', 'metrics.http_req_duration.values.p(99)']);
  if (p50 !== undefined && p95 !== undefined && p99 !== undefined) {
    pushRow(rows, 'Latency', `p50 ${formatMs(p50)} / p95 ${formatMs(p95)} / p99 ${formatMs(p99)}`);
  }

  const vusMax = firstNumber(summary, ['metrics.vus_max.value', 'metrics.vus_max.max', 'metrics.vus_max.values.max']);
  if (vusMax !== undefined) {
    pushRow(rows, 'VUs max', formatCount(vusMax));
  }

  const thresholdValues = Object.values(isObject(summary) && isObject(summary.metrics) ? summary.metrics : {})
    .flatMap((metric) => (isObject(metric) && isObject(metric.thresholds) ? Object.values(metric.thresholds) : []));
  if (thresholdValues.length > 0) {
    const passed = thresholdValues.every((value) => value === true || (isObject(value) && value.ok === true));
    pushRow(rows, 'Thresholds', passed ? 'passed' : 'failed');
  }

  return rows;
}

export function renderToolSummary(rows: ToolSummaryRow[]): string | undefined {
  if (rows.length === 0) return undefined;
  return [
    theme.sectionHeader('Tool Summary'),
    '',
    renderTable(['Metric', 'Value'], rows.map((row) => [theme.label(row.metric), formatToolSummaryValue(row)])),
  ].join('\n');
}

function formatToolSummaryValue(row: ToolSummaryRow): string {
  if (row.metric === 'Native report') {
    return theme.path(row.value);
  }
  if (row.metric === 'Request rate') {
    return theme.info(row.value);
  }
  if (row.metric === 'Thresholds' || row.metric === 'Checks') {
    return theme.semanticValue(row.value);
  }
  if (row.metric === 'Distribution' && row.value.includes('failed 0.00%')) {
    return row.value.replace('failed 0.00%', theme.success('failed 0.00%'));
  }
  return row.value;
}

export function renderToolSummaryFromSummary(tool: ToolSummaryTool, summary: unknown, htmlReportPath?: string): string | undefined {
  const rows = tool === 'gatling'
    ? buildGatlingToolSummaryRows(summary, htmlReportPath)
    : buildK6ToolSummaryRows(summary);
  return renderToolSummary(rows);
}

export function renderToolSummaryFromFile(tool: ToolSummaryTool, summaryPath: string, htmlReportPath?: string): string | undefined {
  try {
    const summary = JSON.parse(readFileSync(summaryPath, 'utf-8'));
    return renderToolSummaryFromSummary(tool, summary, htmlReportPath);
  } catch {
    return undefined;
  }
}
