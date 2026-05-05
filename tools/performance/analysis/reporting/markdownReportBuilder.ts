import type { AIReview } from '../ai/aiReviewModels';
import type { PerformanceComparisonReport, PerformanceReport } from '../domain/models';
import { HIGH_ERROR_RATE_THRESHOLD, HIGH_LATENCY_THRESHOLD_MS } from '../domain/thresholds';

export interface MarkdownReportInput {
  performanceReport?: PerformanceReport;
  comparisonReport?: PerformanceComparisonReport;
  aiReview?: AIReview;
  title?: string;
  toolArtifacts?: {
    gatlingHtmlReportPath?: string;
  };
}

function formatLatency(value: number): string {
  return `${value.toFixed(2)}ms`;
}

function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(2)}%`;
}

function formatThroughput(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

function formatPercentValue(value: number): string {
  return `${value.toFixed(2)}%`;
}

function formatChange(change: number | null): string {
  return change === null ? 'N/A' : `${change.toFixed(2)}%`;
}

function pushBulletList(lines: string[], items: string[], formatItem: (item: string) => string = (item) => item): void {
  for (const item of items) {
    lines.push(`- ${formatItem(item)}`);
  }
}

function formatObservation(item: string): string {
  const latency = item.match(/^(p(?:50|95|99) latency is )(-?\d+(?:\.\d+)?)(ms.*)$/);
  if (latency) {
    return `${latency[1]}${formatLatency(Number(latency[2]))}${latency[3].slice(2)}`;
  }

  const errorRate = item.match(/^(error rate is )(-?\d+(?:\.\d+)?)(.*)$/);
  if (errorRate) {
    return `${errorRate[1]}${formatRate(Number(errorRate[2]))}${errorRate[3]}`;
  }

  const throughput = item.match(/^(throughput is )(-?\d+(?:\.\d+)?)( requests\/second.*)$/);
  if (throughput) {
    return `${throughput[1]}${Number(throughput[2]).toFixed(2)}${throughput[3]}`;
  }

  const usage = item.match(/^((?:CPU|memory|DB pool) usage is )(-?\d+(?:\.\d+)?)(%.*)$/);
  if (usage) {
    return `${usage[1]}${formatPercentValue(Number(usage[2]))}${usage[3].slice(1)}`;
  }

  return item;
}

function isHealthyUnknown(report: PerformanceReport): boolean {
  return (
    report.bottleneck.type === 'UNKNOWN' &&
    report.summary.p95_latency <= HIGH_LATENCY_THRESHOLD_MS &&
    report.summary.error_rate <= HIGH_ERROR_RATE_THRESHOLD
  );
}

function buildPerformanceReportSummary(report: PerformanceReport): string {
  if (isHealthyUnknown(report)) {
    return 'No bottleneck was detected under this load profile. Latency and error rate remained below thresholds.';
  }
  return `Scenario ${report.metadata.scenario_name} produced a ${report.bottleneck.type} bottleneck classification with ${report.bottleneck.confidence} confidence.`;
}

function buildDeterministicExecutiveSummary(input: MarkdownReportInput): string {
  if (input.performanceReport && input.comparisonReport) {
    if (isHealthyUnknown(input.performanceReport)) {
      return `No bottleneck was detected under this load profile. Latency and error rate remained below thresholds. The comparison verdict is ${input.comparisonReport.verdict}.`;
    }
    return `Scenario ${input.performanceReport.metadata.scenario_name} produced a ${input.performanceReport.bottleneck.type} bottleneck classification, and the comparison verdict is ${input.comparisonReport.verdict}.`;
  }
  if (input.performanceReport) {
    return buildPerformanceReportSummary(input.performanceReport);
  }
  if (input.comparisonReport) {
    return `The comparison verdict is ${input.comparisonReport.verdict}.`;
  }
  return 'No deterministic report data was provided.';
}

function appendPerformanceReport(lines: string[], report: PerformanceReport): void {
  lines.push('## Performance Report', '');
  lines.push(`- Scenario: ${report.metadata.scenario_name}`);
  lines.push(`- Test type: ${report.metadata.test_type}`);
  lines.push(`- Timestamp: ${report.metadata.timestamp}`);
  lines.push(`- p95 latency: ${formatLatency(report.summary.p95_latency)}`);
  lines.push(`- error rate: ${formatRate(report.summary.error_rate)}`);
  lines.push(`- throughput: ${formatThroughput(report.summary.throughput)}`);
  lines.push(`- bottleneck type: ${report.bottleneck.type}`);
  lines.push(`- confidence: ${report.bottleneck.confidence}`);
  lines.push('');

  if (report.observations.length > 0) {
    lines.push('### Observations', '');
    pushBulletList(lines, report.observations, formatObservation);
    lines.push('');
  }

  if (report.evidence.length > 0) {
    lines.push('### Evidence', '');
    pushBulletList(lines, report.evidence);
    lines.push('');
  }

  if (report.suggestions.length > 0) {
    lines.push('### Suggestions', '');
    pushBulletList(lines, report.suggestions);
    lines.push('');
  }

  if (report.notes.length > 0) {
    lines.push('### Notes', '');
    pushBulletList(lines, report.notes);
    lines.push('');
  }
}

function appendComparisonReport(lines: string[], report: PerformanceComparisonReport): void {
  lines.push('## Comparison Report', '');
  lines.push(`- Verdict: ${report.verdict}`);
  lines.push(`- Baseline p95 latency: ${formatLatency(report.baseline_summary.p95_latency)}`);
  lines.push(`- Optimized p95 latency: ${formatLatency(report.optimized_summary.p95_latency)}`);
  lines.push(`- Latency change %: ${formatChange(report.improvement.latency_change_percent)}`);
  lines.push(`- Baseline error rate: ${formatRate(report.baseline_summary.error_rate)}`);
  lines.push(`- Optimized error rate: ${formatRate(report.optimized_summary.error_rate)}`);
  lines.push(`- Error change %: ${formatChange(report.improvement.error_change_percent)}`);
  lines.push(`- Baseline throughput: ${formatThroughput(report.baseline_summary.throughput)}`);
  lines.push(`- Optimized throughput: ${formatThroughput(report.optimized_summary.throughput)}`);
  lines.push(`- Throughput change %: ${formatChange(report.improvement.throughput_change_percent)}`);
  lines.push('');

  if (report.interpretation.length > 0) {
    lines.push('### Interpretation', '');
    pushBulletList(lines, report.interpretation);
    lines.push('');
  }
}

function appendAIReview(lines: string[], review: AIReview): void {
  lines.push('## AI Review', '');
  lines.push('### Bottleneck Explanation', '');
  lines.push(review.bottleneckExplanation, '');
  lines.push('### Improvement Assessment', '');
  lines.push(review.improvementAssessment, '');

  if (review.risks.length > 0) {
    lines.push('### Risks', '');
    pushBulletList(lines, review.risks);
    lines.push('');
  }

  if (review.suggestedNextActions.length > 0) {
    lines.push('### Suggested Next Actions', '');
    pushBulletList(lines, review.suggestedNextActions);
    lines.push('');
  }

  if (review.missingEvidence.length > 0) {
    lines.push('### Missing Evidence', '');
    pushBulletList(lines, review.missingEvidence);
    lines.push('');
  }

  lines.push('### Confidence Commentary', '');
  lines.push(review.confidenceCommentary, '');
}

export function renderMarkdownReport(input: MarkdownReportInput): string {
  const lines: string[] = [];
  lines.push(`# ${input.title ?? 'Performance Review'}`, '');
  lines.push('## Executive Summary', '');
  lines.push(input.aiReview?.executiveSummary ?? buildDeterministicExecutiveSummary(input), '');

  if (input.performanceReport) {
    appendPerformanceReport(lines, input.performanceReport);
  }

  if (input.comparisonReport) {
    appendComparisonReport(lines, input.comparisonReport);
  }

  if (input.aiReview) {
    appendAIReview(lines, input.aiReview);
  }

  if (input.toolArtifacts?.gatlingHtmlReportPath) {
    lines.push('## Tool Artifacts', '');
    lines.push(`- Gatling HTML report: ${input.toolArtifacts.gatlingHtmlReportPath}`, '');
  }

  return `${lines.join('\n').replace(/\n+$/, '')}\n`;
}
