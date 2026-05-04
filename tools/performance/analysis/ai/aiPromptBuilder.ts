import type { AIReviewRequest } from './aiReviewModels';

function renderContext(ctx: AIReviewRequest['context']): string {
  if (!ctx) return '';
  const lines: string[] = ['\nCONTEXT:'];
  if (ctx.systemName)   lines.push(`System: ${ctx.systemName}`);
  if (ctx.scenarioName) lines.push(`Scenario: ${ctx.scenarioName}`);
  if (ctx.testType)     lines.push(`Test type: ${ctx.testType}`);
  if (ctx.knownLimitations && ctx.knownLimitations.length > 0) {
    lines.push('Known limitations:');
    for (const l of ctx.knownLimitations) lines.push(`  - ${l}`);
  }
  return lines.join('\n');
}

function renderSingleRun(request: AIReviewRequest): string {
  const r = request.performanceReport;
  if (!r) return '';
  const lines: string[] = [
    'DETERMINISTIC ANALYSIS (do not override):',
    `Bottleneck type: ${r.bottleneck.type}`,
    `Confidence: ${r.bottleneck.confidence}`,
    '',
    'Evidence:',
    ...r.evidence.map((e) => `  - ${e}`),
    '',
    'Suggestions from rule engine:',
    ...r.suggestions.map((s) => `  - ${s}`),
    '',
    'REPORT SUMMARY:',
    `  p95 latency:  ${r.summary.p95_latency}ms`,
    `  error rate:   ${(r.summary.error_rate * 100).toFixed(2)}%`,
    `  throughput:   ${r.summary.throughput} req/s`,
    '',
    'Observations:',
    ...r.observations.map((o) => `  - ${o}`),
  ];
  return lines.join('\n');
}

function renderComparison(request: AIReviewRequest): string {
  const r = request.comparisonReport;
  if (!r) return '';
  const fmt = (v: number | null): string => (v === null ? 'N/A (zero baseline)' : `${v.toFixed(1)}%`);
  const lines: string[] = [
    'DETERMINISTIC COMPARISON (do not override):',
    `Verdict: ${r.verdict}`,
    '',
    'Metric changes:',
    `  p95 latency change:    ${fmt(r.improvement.latency_change_percent)}`,
    `  error rate change:     ${fmt(r.improvement.error_change_percent)}`,
    `  throughput change:     ${fmt(r.improvement.throughput_change_percent)}`,
    '',
    'Interpretation:',
    ...r.interpretation.map((i) => `  - ${i}`),
    '',
    'BASELINE SUMMARY:',
    `  p95 latency:  ${r.baseline_summary.p95_latency}ms`,
    `  error rate:   ${(r.baseline_summary.error_rate * 100).toFixed(2)}%`,
    `  throughput:   ${r.baseline_summary.throughput} req/s`,
    '',
    'OPTIMIZED SUMMARY:',
    `  p95 latency:  ${r.optimized_summary.p95_latency}ms`,
    `  error rate:   ${(r.optimized_summary.error_rate * 100).toFixed(2)}%`,
    `  throughput:   ${r.optimized_summary.throughput} req/s`,
  ];
  return lines.join('\n');
}

export function buildPrompt(request: AIReviewRequest): string {
  const body = request.mode === 'single-run'
    ? renderSingleRun(request)
    : renderComparison(request);

  return [
    'You are a performance engineering reviewer.',
    'The deterministic analysis below was produced by a rule-based classifier.',
    'Return only valid JSON.',
    'Do not include markdown.',
    'Do not wrap output in code fences.',
    'All array fields must be arrays of strings.',
    'Do not override deterministic bottleneck classification.',
    'Do not override deterministic comparison verdict.',
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
