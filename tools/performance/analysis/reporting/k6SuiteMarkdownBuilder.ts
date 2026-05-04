import { basename } from 'node:path';
import type { BottleneckType } from '../domain/models';
import { HIGH_ERROR_RATE_THRESHOLD, HIGH_LATENCY_THRESHOLD_MS } from '../domain/thresholds';
import type { RunK6SuiteResult, RunK6SuiteTestResult } from '../application/runK6Suite';

export interface K6SuiteMarkdownOptions {
  title?: string;
}

function formatLatency(value: number): string {
  return `${value.toFixed(2)}ms`;
}

function formatErrorRate(value: number): string {
  return `${(value * 100).toFixed(2)}%`;
}

function formatThroughput(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

function isHealthyUnknown(result: RunK6SuiteTestResult): boolean {
  return result.report.bottleneck.type === 'UNKNOWN'
    && result.report.summary.p95_latency <= HIGH_LATENCY_THRESHOLD_MS
    && result.report.summary.error_rate <= HIGH_ERROR_RATE_THRESHOLD;
}

function buildExecutiveSummary(result: RunK6SuiteResult): string {
  const stress = result.results.find((r) => r.test === 'stress');
  const nonStress = result.results.filter((r) => r.test !== 'stress');
  if (
    nonStress.length > 0
    && nonStress.every(isHealthyUnknown)
    && stress?.report.bottleneck.type === 'SCALABILITY'
  ) {
    return 'The system is healthy under baseline, load, and spike workloads. Scalability pressure appears under the stress workload.';
  }

  const bottlenecks = Array.from(new Set(
    result.results
      .map((r) => r.report.bottleneck.type)
      .filter((type) => type !== 'UNKNOWN'),
  ));
  if (bottlenecks.length === 0) {
    return 'No bottleneck was detected across the k6 suite.';
  }

  return `The k6 suite found these bottleneck classifications: ${bottlenecks.join(', ')}.`;
}

function interpretationFor(result: RunK6SuiteTestResult): string {
  const type: BottleneckType = result.report.bottleneck.type;
  if (isHealthyUnknown(result)) {
    return `${result.test}: healthy under this workload.`;
  }

  switch (type) {
    case 'SCALABILITY':
      return `${result.test}: latency increases under high concurrency, indicating scalability pressure.`;
    case 'CPU_BOUND':
      return `${result.test}: CPU saturation is the likely bottleneck.`;
    case 'IO_BOUND':
      return `${result.test}: latency appears related to IO or external dependency wait.`;
    case 'CONTENTION':
      return `${result.test}: latency and errors indicate contention or resource saturation.`;
    case 'UNKNOWN':
      return `${result.test}: bottleneck classification remains unknown for this workload.`;
  }
}

export function renderK6SuiteMarkdownReport(result: RunK6SuiteResult, options: K6SuiteMarkdownOptions = {}): string {
  const title = options.title ?? 'k6 Suite Performance Report';
  const rows = result.results.map((r) => [
    `| ${r.test}`,
    formatLatency(r.report.summary.p95_latency),
    formatErrorRate(r.report.summary.error_rate),
    formatThroughput(r.report.summary.throughput),
    r.report.bottleneck.type,
    `${r.report.bottleneck.confidence} |`,
  ].join(' | '));
  const interpretations = result.results.map((r) => `- ${interpretationFor(r)}`);
  if (result.results.some((r) => r.report.notes.length > 0)) {
    interpretations.push('- Some observability data is missing; review individual reports for details.');
  }

  const individualReports = result.results
    .map((r) => basename(r.artifactPaths.markdownPath))
    .filter((name) => name.length > 0)
    .map((name) => `- ${name}`);

  return [
    `# ${title}`,
    '',
    '## Executive Summary',
    '',
    buildExecutiveSummary(result),
    '',
    '## Summary Table',
    '',
    '| Test | p95 latency | Error rate | Throughput | Bottleneck | Confidence |',
    '| --- | ---: | ---: | ---: | --- | --- |',
    ...rows,
    '',
    '## Interpretation',
    '',
    ...interpretations,
    '',
    '## Individual Reports',
    '',
    ...individualReports,
    '',
  ].join('\n');
}
