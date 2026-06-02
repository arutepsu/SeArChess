import { basename } from 'node:path';
import type { RunK6SuiteResult } from '../application/runK6Suite';

export interface K6SuiteHtmlOptions {
  title?: string;
}

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

function formatErrorRate(value: number): string {
  return `${(value * 100).toFixed(2)}%`;
}

function formatThroughput(value: number): string {
  return `${value.toFixed(2)} req/s`;
}

export function renderK6SuiteHtmlReport(result: RunK6SuiteResult, options: K6SuiteHtmlOptions = {}): string {
  const title = options.title ?? 'k6 Suite Performance Report';
  const rows = result.results.map((entry) => `
    <tr>
      <td>${escapeHtml(entry.test)}</td>
      <td>${escapeHtml(formatLatency(entry.report.summary.p95_latency))}</td>
      <td>${escapeHtml(formatErrorRate(entry.report.summary.error_rate))}</td>
      <td>${escapeHtml(formatThroughput(entry.report.summary.throughput))}</td>
      <td>${escapeHtml(entry.report.bottleneck.type)}</td>
      <td>${escapeHtml(entry.report.bottleneck.confidence)}</td>
      <td>${escapeHtml(basename(entry.artifactPaths.markdownPath))}</td>
    </tr>
  `).join('');

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    :root { --border: #d7dde8; --ink: #172033; --muted: #667085; --bg: #f6f8fb; --panel: #ffffff; --accent: #0f6b8f; }
    body { margin: 0; font: 15px/1.5 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: var(--ink); background: var(--bg); }
    main { max-width: 1080px; margin: 0 auto; padding: 32px 20px 48px; }
    h1 { margin: 0 0 8px; font-size: 30px; line-height: 1.15; }
    h2 { margin: 0 0 12px; font-size: 20px; color: var(--accent); }
    section { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 18px 20px; margin: 16px 0; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border-top: 1px solid var(--border); padding: 9px 8px; text-align: left; vertical-align: top; }
    thead th { border-top: 0; color: var(--muted); font-weight: 650; }
  </style>
</head>
<body>
  <main>
    <header>
      <h1>${escapeHtml(title)}</h1>
      <p>Searchess normalized deterministic results for the k6 suite.</p>
    </header>
    <section>
      <h2>Suite Results</h2>
      <table>
        <thead>
          <tr>
            <th>Test</th>
            <th>p95 latency</th>
            <th>Error rate</th>
            <th>Throughput</th>
            <th>Bottleneck</th>
            <th>Diagnosis confidence</th>
            <th>Markdown report</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </section>
  </main>
</body>
</html>
`;
}
