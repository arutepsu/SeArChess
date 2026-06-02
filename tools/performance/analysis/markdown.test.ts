import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { AIReview } from './ai/aiReviewModels';
import type { PerformanceComparisonReport, PerformanceReport } from './domain/models';
import { renderPerformanceReviewHtml } from './application/renderPerformanceReviewHtml';
import { renderMarkdownReport } from './reporting/markdownReportBuilder';

const RENDER = join(__dirname, 'cli', 'render.js');

const performanceReport: PerformanceReport = {
  metadata:     { test_type: 'load', scenario_name: 'api', timestamp: '2026-05-04T00:00:00Z' },
  summary:      { p95_latency: 600, error_rate: 0.02, throughput: 400 },
  observations: ['p95 latency is elevated'],
  bottleneck:   { type: 'CPU_BOUND', confidence: 'HIGH' },
  evidence:     ['CPU usage exceeded threshold'],
  suggestions:  ['Profile CPU-heavy code paths'],
  notes:        ['db pool metric was unavailable'],
};

const comparisonReport: PerformanceComparisonReport = {
  baseline_summary:  { p95_latency: 600, error_rate: 0.02, throughput: 400 },
  optimized_summary: { p95_latency: 450, error_rate: 0.01, throughput: 500 },
  improvement: {
    latency_change_percent:    -25,
    error_change_percent:      -50,
    throughput_change_percent:  null,
  },
  interpretation: ['p95 latency improved by 25.0%'],
  verdict: 'SUCCESS',
};

const aiReview: AIReview = {
  executiveSummary:       'AI summary: CPU pressure is the main concern.',
  bottleneckExplanation:  'The deterministic report classifies the run as CPU-bound.',
  improvementAssessment:  'The optimized run improves latency.',
  risks:                  ['CPU saturation may reappear at higher load'],
  suggestedNextActions:   ['Inspect hot paths'],
  missingEvidence:        ['No flame graph was provided'],
  confidenceCommentary:   'Confidence follows the deterministic evidence.',
};

function writeTmp(dir: string, name: string, content: string): string {
  const filePath = join(dir, name);
  writeFileSync(filePath, content);
  return filePath;
}

test('renderMarkdownReport renders PerformanceReport only', () => {
  const markdown = renderMarkdownReport({ performanceReport });
  assert.ok(markdown.includes('# Performance Review'));
  assert.ok(markdown.includes('## Performance Report'));
  assert.ok(markdown.includes('CPU_BOUND'));
  assert.ok(markdown.includes('2.00%'));
  assert.ok(markdown.endsWith('\n'));
});

test('renderPerformanceReviewHtml escapes dynamic content', () => {
  const html = renderPerformanceReviewHtml({
    title: '<Searchess Report>',
    performanceReport: {
      ...performanceReport,
      metadata: { ...performanceReport.metadata, scenario_name: '<script>alert("x")</script>' },
      evidence: ['CPU > 90% & payload <hidden>'],
      suggestions: ['Inspect "hot" path'],
    },
  });

  assert.ok(html.includes('&lt;Searchess Report&gt;'));
  assert.ok(html.includes('&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;'));
  assert.ok(html.includes('CPU &gt; 90% &amp; payload &lt;hidden&gt;'));
  assert.ok(!html.includes('<script>alert("x")</script>'));
});

test('renderPerformanceReviewHtml includes title and core result metrics', () => {
  const html = renderPerformanceReviewHtml({ title: 'Workbench HTML', performanceReport });

  assert.ok(html.includes('<title>Workbench HTML</title>'));
  assert.ok(html.includes('p95 latency'));
  assert.ok(html.includes('600.00ms'));
  assert.ok(html.includes('Error rate'));
  assert.ok(html.includes('2.00%'));
  assert.ok(html.includes('400.00 req/s'));
});

test('renderPerformanceReviewHtml includes bottleneck confidence evidence and suggestions', () => {
  const html = renderPerformanceReviewHtml({ performanceReport });

  assert.ok(html.includes('CPU_BOUND'));
  assert.ok(html.includes('Diagnosis confidence'));
  assert.ok(html.includes('HIGH'));
  assert.ok(html.includes('CPU usage exceeded threshold'));
  assert.ok(html.includes('Profile CPU-heavy code paths'));
});

test('renderMarkdownReport rounds performance metrics for display', () => {
  const report: PerformanceReport = {
    metadata:     { test_type: 'load', scenario_name: 'gatling-load-baseline', timestamp: '2026-05-05T00:00:00Z' },
    summary:      { p95_latency: 53, error_rate: 0, throughput: 61.904761904761905 },
    observations: [
      'p50 latency is 9ms',
      'p95 latency is 53ms',
      'p99 latency is 60ms',
      'error rate is 0 (0 total errors)',
      'throughput is 61.904761904761905 requests/second',
      'CPU usage is 72%',
      'memory usage is 61%',
    ],
    bottleneck:   { type: 'UNKNOWN', confidence: 'LOW' },
    evidence:     [],
    suggestions:  [],
    notes:        [],
  };

  const markdown = renderMarkdownReport({ performanceReport: report });

  assert.ok(markdown.includes('- p95 latency: 53.00ms'));
  assert.ok(markdown.includes('- error rate: 0.00%'));
  assert.ok(markdown.includes('- throughput: 61.90 req/s'));
  assert.ok(markdown.includes('- p50 latency is 9.00ms'));
  assert.ok(markdown.includes('- p99 latency is 60.00ms'));
  assert.ok(markdown.includes('- throughput is 61.90 requests/second'));
  assert.ok(markdown.includes('- CPU usage is 72.00%'));
  assert.ok(markdown.includes('- memory usage is 61.00%'));
});

test('renderMarkdownReport can include a Gatling native HTML report path', () => {
  const markdown = renderMarkdownReport({
    performanceReport,
    toolArtifacts: {
      gatlingHtmlReportPath: 'C:\\repo\\tools\\performance\\gatling\\target\\gatling\\run\\index.html',
    },
  });

  assert.ok(markdown.includes('## Tool Artifacts'));
  assert.ok(markdown.includes('Gatling HTML report: C:\\repo\\tools\\performance\\gatling\\target\\gatling\\run\\index.html'));
});

test('renderMarkdownReport summarizes healthy UNKNOWN as no bottleneck detected', () => {
  const healthyUnknownReport: PerformanceReport = {
    metadata:     { test_type: 'load', scenario_name: 'healthy-api', timestamp: '2026-05-04T00:00:00Z' },
    summary:      { p95_latency: 250, error_rate: 0.001, throughput: 500 },
    observations: ['p95 latency is below threshold'],
    bottleneck:   { type: 'UNKNOWN', confidence: 'LOW' },
    evidence:     ['No strong bottleneck signal detected'],
    suggestions:  ['No immediate optimization action is required for this load profile'],
    notes:        [],
  };
  const markdown = renderMarkdownReport({ performanceReport: healthyUnknownReport });
  assert.ok(markdown.includes('No bottleneck was detected under this load profile.'));
  assert.ok(!markdown.includes('produced a UNKNOWN bottleneck classification'));
});

test('renderMarkdownReport renders PerformanceComparisonReport only', () => {
  const markdown = renderMarkdownReport({ comparisonReport });
  assert.ok(markdown.includes('## Comparison Report'));
  assert.ok(markdown.includes('SUCCESS'));
  assert.ok(markdown.includes('-25.00%'));
  assert.ok(markdown.includes('N/A'));
});

test('renderMarkdownReport renders AIReview only', () => {
  const markdown = renderMarkdownReport({ aiReview });
  assert.ok(markdown.includes('## Executive Summary'));
  assert.ok(markdown.includes('AI summary: CPU pressure is the main concern.'));
  assert.ok(markdown.includes('## AI Review'));
  assert.ok(markdown.includes('### Bottleneck Explanation'));
});

test('renderMarkdownReport renders all report inputs together', () => {
  const markdown = renderMarkdownReport({
    performanceReport,
    comparisonReport,
    aiReview,
    title: 'Nightly Performance Review',
  });
  assert.ok(markdown.includes('# Nightly Performance Review'));
  assert.ok(markdown.includes('## Performance Report'));
  assert.ok(markdown.includes('## Comparison Report'));
  assert.ok(markdown.includes('## AI Review'));
  assert.ok(markdown.includes('CPU_BOUND'));
  assert.ok(markdown.includes('SUCCESS'));
  assert.ok(markdown.includes('AI summary: CPU pressure is the main concern.'));
});

test('cli render: exits 0 and prints Markdown for valid render input', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-render-'));
  const inPath = writeTmp(dir, 'render-input.json', JSON.stringify({ performanceReport, comparisonReport, aiReview }));
  const result = spawnSync('node', [RENDER, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  assert.ok(result.stdout.includes('# Performance Review'));
  assert.ok(result.stdout.includes('## Performance Report'));
  assert.ok(result.stdout.includes('## Comparison Report'));
  assert.ok(result.stdout.includes('## AI Review'));
});

test('cli render: exits 1 when argument is missing', () => {
  const result = spawnSync('node', [RENDER], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Usage'));
});

test('cli render: exits 1 for invalid JSON', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-render-'));
  const inPath = writeTmp(dir, 'bad.json', 'not json');
  const result = spawnSync('node', [RENDER, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.length > 0);
});

test('cli render: exits 1 when no renderable report input exists', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-render-'));
  const inPath = writeTmp(dir, 'empty.json', JSON.stringify({ title: 'Empty' }));
  const result = spawnSync('node', [RENDER, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('expected performanceReport, comparisonReport, or aiReview'));
});
