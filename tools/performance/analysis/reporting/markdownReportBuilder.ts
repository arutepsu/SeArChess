import type { AIReview } from '../ai/aiReviewModels';
import type { PerformanceComparisonReport, PerformanceReport } from '../domain/models';

export interface MarkdownReportInput {
  performanceReport?: PerformanceReport;
  comparisonReport?: PerformanceComparisonReport;
  aiReview?: AIReview;
  title?: string;
}

function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(2)}%`;
}

function formatChange(change: number | null): string {
  return change === null ? 'N/A' : `${change.toFixed(2)}%`;
}

function pushBulletList(lines: string[], items: string[]): void {
  for (const item of items) {
    lines.push(`- ${item}`);
  }
}

function buildDeterministicExecutiveSummary(input: MarkdownReportInput): string {
  if (input.performanceReport && input.comparisonReport) {
    return `Scenario ${input.performanceReport.metadata.scenario_name} produced a ${input.performanceReport.bottleneck.type} bottleneck classification, and the comparison verdict is ${input.comparisonReport.verdict}.`;
  }
  if (input.performanceReport) {
    return `Scenario ${input.performanceReport.metadata.scenario_name} produced a ${input.performanceReport.bottleneck.type} bottleneck classification with ${input.performanceReport.bottleneck.confidence} confidence.`;
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
  lines.push(`- p95 latency: ${report.summary.p95_latency}ms`);
  lines.push(`- error rate: ${formatRate(report.summary.error_rate)}`);
  lines.push(`- throughput: ${report.summary.throughput} req/s`);
  lines.push(`- bottleneck type: ${report.bottleneck.type}`);
  lines.push(`- confidence: ${report.bottleneck.confidence}`);
  lines.push('');

  if (report.observations.length > 0) {
    lines.push('### Observations', '');
    pushBulletList(lines, report.observations);
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
  lines.push(`- Baseline p95 latency: ${report.baseline_summary.p95_latency}ms`);
  lines.push(`- Optimized p95 latency: ${report.optimized_summary.p95_latency}ms`);
  lines.push(`- Latency change %: ${formatChange(report.improvement.latency_change_percent)}`);
  lines.push(`- Baseline error rate: ${formatRate(report.baseline_summary.error_rate)}`);
  lines.push(`- Optimized error rate: ${formatRate(report.optimized_summary.error_rate)}`);
  lines.push(`- Error change %: ${formatChange(report.improvement.error_change_percent)}`);
  lines.push(`- Baseline throughput: ${report.baseline_summary.throughput} req/s`);
  lines.push(`- Optimized throughput: ${report.optimized_summary.throughput} req/s`);
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

  return `${lines.join('\n').replace(/\n+$/, '')}\n`;
}
