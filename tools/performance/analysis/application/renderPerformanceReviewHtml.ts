import type { MarkdownReportInput } from '../reporting/markdownReportBuilder';

function escapeHtml(value: unknown): string {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatLatency(value: number): string {
  return `${value.toFixed(2)}ms`;
}

function formatRate(value: number): string {
  return `${(value * 100).toFixed(2)}%`;
}

function formatThroughput(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

function formatChange(value: number | null): string {
  return value === null ? 'N/A' : `${value.toFixed(2)}%`;
}

function list(items: string[]): string {
  if (items.length === 0) return '<p class="muted">None.</p>';
  return `<ul>${items.map((item) => `<li>${escapeHtml(item)}</li>`).join('')}</ul>`;
}

function rows(values: Array<[string, string]>): string {
  return values
    .map(([label, value]) => `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(value)}</td></tr>`)
    .join('');
}

export function renderPerformanceReviewHtml(input: MarkdownReportInput): string {
  const title = input.title ?? 'Performance Review';
  const report = input.performanceReport;
  const comparison = input.comparisonReport;
  const aiReview = input.aiReview;
  const summary = aiReview?.executiveSummary
    ?? (report
      ? `Scenario ${report.metadata.scenario_name} produced a ${report.bottleneck.type} bottleneck classification with ${report.bottleneck.confidence} diagnosis confidence.`
      : comparison
        ? `The comparison verdict is ${comparison.verdict}.`
        : 'No deterministic report data was provided.');

  const performanceSection = report ? `
    <section>
      <h2>Result</h2>
      <table>
        <tbody>
          ${rows([
            ['Scenario', report.metadata.scenario_name],
            ['Test type', report.metadata.test_type],
            ['Timestamp', report.metadata.timestamp],
            ['p95 latency', formatLatency(report.summary.p95_latency)],
            ['Error rate', formatRate(report.summary.error_rate)],
            ['Throughput', formatThroughput(report.summary.throughput)],
          ])}
        </tbody>
      </table>
    </section>
    <section>
      <h2>Bottleneck</h2>
      <table>
        <tbody>
          ${rows([
            ['Type', report.bottleneck.type],
            ['Diagnosis confidence', report.bottleneck.confidence],
          ])}
        </tbody>
      </table>
    </section>
    <section><h2>Evidence</h2>${list(report.evidence)}</section>
    <section><h2>Suggestions</h2>${list(report.suggestions)}</section>
    <section><h2>Notes</h2>${list(report.notes)}</section>
  ` : '';

  const comparisonSection = comparison ? `
    <section>
      <h2>Comparison</h2>
      <table>
        <tbody>
          ${rows([
            ['Verdict', comparison.verdict],
            ['Baseline p95 latency', formatLatency(comparison.baseline_summary.p95_latency)],
            ['Optimized p95 latency', formatLatency(comparison.optimized_summary.p95_latency)],
            ['Latency change', formatChange(comparison.improvement.latency_change_percent)],
            ['Baseline error rate', formatRate(comparison.baseline_summary.error_rate)],
            ['Optimized error rate', formatRate(comparison.optimized_summary.error_rate)],
            ['Error change', formatChange(comparison.improvement.error_change_percent)],
            ['Baseline throughput', formatThroughput(comparison.baseline_summary.throughput)],
            ['Optimized throughput', formatThroughput(comparison.optimized_summary.throughput)],
            ['Throughput change', formatChange(comparison.improvement.throughput_change_percent)],
          ])}
        </tbody>
      </table>
      <h3>Interpretation</h3>
      ${list(comparison.interpretation)}
    </section>
  ` : '';

  const aiSection = aiReview ? `
    <section>
      <h2>AI Review</h2>
      <h3>Bottleneck Explanation</h3>
      <p>${escapeHtml(aiReview.bottleneckExplanation)}</p>
      <h3>Improvement Assessment</h3>
      <p>${escapeHtml(aiReview.improvementAssessment)}</p>
      <h3>Risks</h3>${list(aiReview.risks)}
      <h3>Suggested Next Actions</h3>${list(aiReview.suggestedNextActions)}
      <h3>Missing Evidence</h3>${list(aiReview.missingEvidence)}
      <h3>Confidence Commentary</h3>
      <p>${escapeHtml(aiReview.confidenceCommentary)}</p>
    </section>
  ` : '';

  const artifactSection = input.toolArtifacts?.gatlingHtmlReportPath ? `
    <section>
      <h2>Tool Artifacts</h2>
      <table>
        <tbody>
          ${rows([['Gatling HTML report', input.toolArtifacts.gatlingHtmlReportPath]])}
        </tbody>
      </table>
    </section>
  ` : '';

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    :root { color-scheme: light; --border: #d7dde8; --ink: #172033; --muted: #667085; --bg: #f6f8fb; --panel: #ffffff; --accent: #0f6b8f; }
    body { margin: 0; font: 15px/1.5 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: var(--ink); background: var(--bg); }
    main { max-width: 980px; margin: 0 auto; padding: 32px 20px 48px; }
    header { margin-bottom: 24px; }
    h1 { margin: 0 0 8px; font-size: 30px; line-height: 1.15; }
    h2 { margin: 0 0 12px; font-size: 20px; color: var(--accent); }
    h3 { margin: 16px 0 8px; font-size: 16px; }
    section { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 18px 20px; margin: 16px 0; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border-top: 1px solid var(--border); padding: 9px 8px; text-align: left; vertical-align: top; }
    tr:first-child th, tr:first-child td { border-top: 0; }
    th { width: 230px; color: var(--muted); font-weight: 650; }
    ul { margin: 0; padding-left: 20px; }
    .muted { color: var(--muted); }
  </style>
</head>
<body>
  <main>
    <header>
      <h1>${escapeHtml(title)}</h1>
      <p>${escapeHtml(summary)}</p>
    </header>
    ${performanceSection}
    ${comparisonSection}
    ${aiSection}
    ${artifactSection}
  </main>
</body>
</html>
`;
}
