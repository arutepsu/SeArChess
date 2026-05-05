import type { AIReviewRequest } from './aiReviewModels';

function renderContext(ctx: AIReviewRequest['context']): string {
  if (!ctx) return '';

  const lines: string[] = ['\nCONTEXT:'];

  if (ctx.systemName) lines.push(`System: ${ctx.systemName}`);
  if (ctx.scenarioName) lines.push(`Scenario: ${ctx.scenarioName}`);
  if (ctx.testType) lines.push(`Test type: ${ctx.testType}`);

  if (ctx.knownLimitations && ctx.knownLimitations.length > 0) {
    lines.push('Known limitations:');
    for (const limitation of ctx.knownLimitations) {
      lines.push(`  - ${limitation}`);
    }
  }

  return lines.join('\n');
}

function renderSingleRun(request: AIReviewRequest): string {
  const report = request.performanceReport;
  if (!report) {
    return [
      'DETERMINISTIC ANALYSIS:',
      'No deterministic performance report was provided.',
    ].join('\n');
  }

  return [
    'DETERMINISTIC ANALYSIS (do not override):',
    `Bottleneck type: ${report.bottleneck.type}`,
    `Confidence: ${report.bottleneck.confidence}`,
    '',
    'Evidence:',
    ...report.evidence.map((evidence) => `  - ${evidence}`),
    '',
    'Suggestions from rule engine:',
    ...report.suggestions.map((suggestion) => `  - ${suggestion}`),
    '',
    'REPORT SUMMARY:',
    `  Scenario:     ${report.metadata.scenario_name}`,
    `  Test type:    ${report.metadata.test_type}`,
    `  p95 latency:  ${report.summary.p95_latency}ms`,
    `  error rate:   ${(report.summary.error_rate * 100).toFixed(2)}%`,
    `  throughput:   ${report.summary.throughput} req/s`,
    '',
    'Observations:',
    ...report.observations.map((observation) => `  - ${observation}`),
  ].join('\n');
}

function renderComparison(request: AIReviewRequest): string {
  const report = request.comparisonReport;
  if (!report) {
    return [
      'DETERMINISTIC COMPARISON:',
      'No deterministic comparison report was provided.',
    ].join('\n');
  }

  const fmt = (value: number | null): string =>
    value === null ? 'N/A (zero baseline)' : `${value.toFixed(1)}%`;

  return [
    'DETERMINISTIC COMPARISON (do not override):',
    `Verdict: ${report.verdict}`,
    '',
    'Metric changes:',
    `  p95 latency change:    ${fmt(report.improvement.latency_change_percent)}`,
    `  error rate change:     ${fmt(report.improvement.error_change_percent)}`,
    `  throughput change:     ${fmt(report.improvement.throughput_change_percent)}`,
    '',
    'Interpretation:',
    ...report.interpretation.map((line) => `  - ${line}`),
    '',
    'BASELINE SUMMARY:',
    `  p95 latency:  ${report.baseline_summary.p95_latency}ms`,
    `  error rate:   ${(report.baseline_summary.error_rate * 100).toFixed(2)}%`,
    `  throughput:   ${report.baseline_summary.throughput} req/s`,
    '',
    'OPTIMIZED SUMMARY:',
    `  p95 latency:  ${report.optimized_summary.p95_latency}ms`,
    `  error rate:   ${(report.optimized_summary.error_rate * 100).toFixed(2)}%`,
    `  throughput:   ${report.optimized_summary.throughput} req/s`,
  ].join('\n');
}

function renderReportSuite(request: AIReviewRequest): string {
  const reports = request.performanceReports ?? [];

  if (reports.length === 0) {
    return [
      'DETERMINISTIC REPORT SUITE:',
      'No deterministic performance reports were provided for this suite review.',
    ].join('\n');
  }

  const lines: string[] = [
    'DETERMINISTIC REPORT SUITE (do not override any included classification):',
    '',
  ];

  reports.forEach((report, index) => {
    lines.push(
      `REPORT ${index + 1}`,
      `Scenario: ${report.metadata.scenario_name}`,
      `Test type: ${report.metadata.test_type}`,
      `Bottleneck type: ${report.bottleneck.type}`,
      `Confidence: ${report.bottleneck.confidence}`,
      '',
      'Summary:',
      `  p95 latency:  ${report.summary.p95_latency}ms`,
      `  error rate:   ${(report.summary.error_rate * 100).toFixed(2)}%`,
      `  throughput:   ${report.summary.throughput} req/s`,
      '',
      'Evidence:',
      ...report.evidence.map((evidence) => `  - ${evidence}`),
      '',
      'Suggestions from rule engine:',
      ...report.suggestions.map((suggestion) => `  - ${suggestion}`),
      '',
      'Observations:',
      ...report.observations.map((observation) => `  - ${observation}`),
      '',
    );
  });

  return lines.join('\n');
}

function renderBody(request: AIReviewRequest): string {
  switch (request.mode) {
    case 'single-run':
      return renderSingleRun(request);
    case 'comparison':
      return renderComparison(request);
    case 'report-suite':
      return renderReportSuite(request);
  }
}

export function buildPrompt(request: AIReviewRequest): string {
  const body = renderBody(request);

  return [
    'You are a performance engineering reviewer.',
    'The deterministic analysis below was produced by a rule-based classifier.',
    'Return only valid JSON.',
    'Do not include markdown.',
    'Do not wrap output in code fences.',
    'All array fields must be arrays of strings.',
    'Do not override deterministic bottleneck classification.',
    'Do not override deterministic comparison verdict.',
    'Do not override deterministic bottleneck classifications for any included report.',
    'For report suites, summarize cross-workload behavior and identify the workload with strongest pressure.',
    'Do not invent metrics, measurements, thresholds, services, or causes not present in the provided data.',
    'Base conclusions only on the provided report data.',
    '',
    body,
    renderContext(request.context),
    '',
    'Return a JSON object with these fields:',
    '  executiveSummary, bottleneckExplanation, improvementAssessment,',
    '  risks (array), suggestedNextActions (array), missingEvidence (array), confidenceCommentary',
  ].join('\n');
}