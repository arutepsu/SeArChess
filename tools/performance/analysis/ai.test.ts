import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildPrompt } from './ai/aiPromptBuilder';
import { StubAIReviewProvider } from './ai/aiReviewProvider';
import { createAIReviewProvider } from './ai/aiReviewProviderFactory';
import { runAIReview } from './ai/aiReviewService';
import type { AIReviewProvider, AIReviewRequest } from './ai/aiReviewModels';
import type { PerformanceReport, PerformanceComparisonReport } from './domain/models';
import { validateAIReview } from './validation/validateAIReview';

const REVIEW = join(__dirname, 'cli', 'review.js');

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const singleReport: PerformanceReport = {
  metadata:     { test_type: 'load', scenario_name: 'api', timestamp: '2026-05-04T00:00:00Z' },
  summary:      { p95_latency: 600, error_rate: 0.01, throughput: 400 },
  observations: ['p95 latency is 600ms', 'CPU usage is 90%'],
  bottleneck:   { type: 'CPU_BOUND', confidence: 'HIGH' },
  evidence:     ['p95 600ms exceeds 500ms threshold', 'CPU at 90% exceeds 80% threshold'],
  suggestions:  ['Optimize CPU-intensive code paths', 'Consider horizontal scaling'],
  notes:        [],
};

const suiteLoadReport: PerformanceReport = {
  metadata:     { test_type: 'load', scenario_name: 'k6-load-baseline', timestamp: '2026-05-04T00:00:00Z' },
  summary:      { p95_latency: 120, error_rate: 0, throughput: 500 },
  observations: ['load workload remained stable'],
  bottleneck:   { type: 'UNKNOWN', confidence: 'LOW' },
  evidence:     ['p95 latency stayed below threshold'],
  suggestions:  ['No immediate optimization action is required'],
  notes:        [],
};

const suiteStressReport: PerformanceReport = {
  metadata:     { test_type: 'stress', scenario_name: 'k6-stress-baseline', timestamp: '2026-05-04T00:03:00Z' },
  summary:      { p95_latency: 1200, error_rate: 0.01, throughput: 780 },
  observations: ['stress workload increased latency'],
  bottleneck:   { type: 'SCALABILITY', confidence: 'MEDIUM' },
  evidence:     ['p95 latency increased under high concurrency'],
  suggestions:  ['Investigate scaling limits under stress workload'],
  notes:        [],
};

const comparisonReport: PerformanceComparisonReport = {
  baseline_summary:  { p95_latency: 600, error_rate: 0.05, throughput: 400 },
  optimized_summary: { p95_latency: 400, error_rate: 0.02, throughput: 500 },
  improvement: {
    latency_change_percent:    -33.3,
    error_change_percent:      -60,
    throughput_change_percent:  25,
  },
  interpretation: [
    'p95 latency improved by 33.3%',
    'error rate improved by 60.0%',
    'throughput improved by 25.0%',
  ],
  verdict: 'SUCCESS',
};

// ---------------------------------------------------------------------------
// Prompt builder
// ---------------------------------------------------------------------------

test('prompt builder includes bottleneck type and evidence for single-run', () => {
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  const prompt = buildPrompt(request);
  assert.ok(prompt.includes('CPU_BOUND'),                          'missing bottleneck type');
  assert.ok(prompt.includes('p95 600ms exceeds 500ms threshold'),  'missing evidence line 1');
  assert.ok(prompt.includes('CPU at 90% exceeds 80% threshold'),   'missing evidence line 2');
});

test('prompt builder includes suggestions for single-run', () => {
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  const prompt = buildPrompt(request);
  assert.ok(prompt.includes('Optimize CPU-intensive code paths'));
});

test('prompt builder instructs AI not to override deterministic classification', () => {
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  const prompt = buildPrompt(request);
  assert.ok(prompt.toLowerCase().includes('do not override') || prompt.toLowerCase().includes('not override'));
});

test('prompt builder includes strict JSON-only instruction', () => {
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  const prompt = buildPrompt(request);
  assert.ok(prompt.includes('Return only valid JSON'));
  assert.ok(prompt.includes('Do not include markdown'));
  assert.ok(prompt.includes('Do not wrap output in code fences'));
  assert.ok(prompt.includes('All array fields must be arrays of strings'));
});

test('prompt builder includes comparison verdict when mode is comparison', () => {
  const request: AIReviewRequest = { mode: 'comparison', comparisonReport: comparisonReport };
  const prompt = buildPrompt(request);
  assert.ok(prompt.includes('SUCCESS'));
  assert.ok(prompt.includes('-33.3%') || prompt.includes('-33'));
});

test('prompt builder includes interpretation lines for comparison', () => {
  const request: AIReviewRequest = { mode: 'comparison', comparisonReport: comparisonReport };
  const prompt = buildPrompt(request);
  assert.ok(prompt.includes('p95 latency improved by 33.3%'));
});

test('prompt builder includes optional context when provided', () => {
  const request: AIReviewRequest = {
    mode: 'single-run',
    performanceReport: singleReport,
    context: { systemName: 'search-api', knownLimitations: ['cold start excluded'] },
  };
  const prompt = buildPrompt(request);
  assert.ok(prompt.includes('search-api'));
  assert.ok(prompt.includes('cold start excluded'));
});

test('prompt builder for report-suite includes data from multiple reports', () => {
  const request: AIReviewRequest = {
    mode: 'report-suite',
    performanceReports: [suiteLoadReport, suiteStressReport],
  };
  const prompt = buildPrompt(request);

  assert.ok(prompt.includes('DETERMINISTIC REPORT SUITE'));
  assert.ok(prompt.includes('REPORT 1'));
  assert.ok(prompt.includes('REPORT 2'));
  assert.ok(prompt.includes('120ms'));
  assert.ok(prompt.includes('1200ms'));
  assert.ok(prompt.includes('500 req/s'));
  assert.ok(prompt.includes('780 req/s'));
});

test('prompt builder for report-suite includes scenario names and test types', () => {
  const prompt = buildPrompt({
    mode: 'report-suite',
    performanceReports: [suiteLoadReport, suiteStressReport],
  });

  assert.ok(prompt.includes('k6-load-baseline'));
  assert.ok(prompt.includes('Test type: load'));
  assert.ok(prompt.includes('k6-stress-baseline'));
  assert.ok(prompt.includes('Test type: stress'));
});

test('prompt builder for report-suite includes bottleneck classifications from all reports', () => {
  const prompt = buildPrompt({
    mode: 'report-suite',
    performanceReports: [suiteLoadReport, suiteStressReport],
  });

  assert.ok(prompt.includes('Bottleneck type: UNKNOWN'));
  assert.ok(prompt.includes('Bottleneck type: SCALABILITY'));
  assert.ok(prompt.includes('Do not override deterministic bottleneck classifications for any included report.'));
  assert.ok(prompt.includes('strongest pressure'));
});

test('prompt builder for report-suite does not fall back to comparison rendering', () => {
  const prompt = buildPrompt({
    mode: 'report-suite',
    performanceReports: [suiteLoadReport, suiteStressReport],
  });

  assert.ok(!prompt.includes('DETERMINISTIC COMPARISON'));
  assert.ok(!prompt.includes('Verdict:'));
  assert.ok(prompt.includes('DETERMINISTIC REPORT SUITE'));
});

test('prompt builder for empty report-suite includes diagnostic section', () => {
  const prompt = buildPrompt({
    mode: 'report-suite',
    performanceReports: [],
  });

  assert.ok(prompt.includes('DETERMINISTIC REPORT SUITE'));
  assert.ok(prompt.includes('No deterministic performance reports were provided'));
});

// ---------------------------------------------------------------------------
// Stub provider
// ---------------------------------------------------------------------------

test('provider factory returns StubAIReviewProvider when env is empty', () => {
  const provider = createAIReviewProvider({});
  assert.ok(provider instanceof StubAIReviewProvider);
});

test('provider factory returns StubAIReviewProvider when PERF_AI_PROVIDER is stub', () => {
  const provider = createAIReviewProvider({ PERF_AI_PROVIDER: 'stub' });
  assert.ok(provider instanceof StubAIReviewProvider);
});

test('provider factory throws clear error for unsupported provider', () => {
  assert.throws(
    () => createAIReviewProvider({ PERF_AI_PROVIDER: 'openai' }),
    /Unsupported PERF_AI_PROVIDER: openai/,
  );
});

test('stub provider returns deterministic AIReview', async () => {
  const provider = new StubAIReviewProvider();
  const review1  = await provider.review('any prompt');
  const review2  = await provider.review('different prompt');
  assert.equal(review1.executiveSummary,      review2.executiveSummary);
  assert.equal(review1.confidenceCommentary,  review2.confidenceCommentary);
  assert.ok(Array.isArray(review1.risks));
  assert.ok(Array.isArray(review1.suggestedNextActions));
  assert.ok(Array.isArray(review1.missingEvidence));
});

test('stub provider returns valid AIReview', async () => {
  const provider = new StubAIReviewProvider();
  const review = await provider.review('any prompt');
  assert.deepEqual(validateAIReview(review), []);
});

test('stub provider makes no network calls and resolves immediately', async () => {
  const provider = new StubAIReviewProvider();
  const start    = Date.now();
  await provider.review('test');
  assert.ok(Date.now() - start < 100, 'stub took unexpectedly long — may be making network calls');
});

// ---------------------------------------------------------------------------
// Review service
// ---------------------------------------------------------------------------

test('review service calls provider and returns AIReview', async () => {
  const provider = new StubAIReviewProvider();
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  const review = await runAIReview(request, provider);
  assert.ok(typeof review.executiveSummary      === 'string');
  assert.ok(typeof review.bottleneckExplanation === 'string');
  assert.ok(typeof review.improvementAssessment === 'string');
  assert.ok(typeof review.confidenceCommentary  === 'string');
  assert.ok(Array.isArray(review.risks));
  assert.ok(Array.isArray(review.suggestedNextActions));
  assert.ok(Array.isArray(review.missingEvidence));
});

test('validateAIReview rejects malformed AI output', () => {
  const errors = validateAIReview({
    executiveSummary: 42,
    bottleneckExplanation: 'ok',
    improvementAssessment: 'ok',
    risks: ['risk'],
    suggestedNextActions: [123],
    missingEvidence: 'missing',
    confidenceCommentary: 'ok',
  });
  assert.ok(errors.includes('executiveSummary must be a string'));
  assert.ok(errors.includes('suggestedNextActions must be an array of strings'));
  assert.ok(errors.includes('missingEvidence must be an array of strings'));
});

test('review service throws if provider returns malformed AI output', async () => {
  const badProvider = {
    async review() {
      return {
        executiveSummary: 'ok',
        bottleneckExplanation: 'ok',
        improvementAssessment: 'ok',
        risks: ['risk'],
        suggestedNextActions: ['next'],
        missingEvidence: [123],
        confidenceCommentary: 'ok',
      };
    },
  } as unknown as AIReviewProvider;
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  await assert.rejects(
    () => runAIReview(request, badProvider),
    /Invalid AIReview provider output/,
  );
});

test('review service passes prompt containing report data to provider', async () => {
  let capturedPrompt = '';
  const capturingProvider = {
    async review(prompt: string) {
      capturedPrompt = prompt;
      return new StubAIReviewProvider().review(prompt);
    },
  };
  const request: AIReviewRequest = { mode: 'single-run', performanceReport: singleReport };
  await runAIReview(request, capturingProvider);
  assert.ok(capturedPrompt.includes('CPU_BOUND'));
});

// ---------------------------------------------------------------------------
// CLI review command
// ---------------------------------------------------------------------------

function writeTmp(dir: string, name: string, content: unknown): string {
  const filePath = join(dir, name);
  writeFileSync(filePath, JSON.stringify(content));
  return filePath;
}

test('cli review: exits 0 and prints AIReview JSON for a PerformanceReport file', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const inPath = writeTmp(dir, 'report.json', singleReport);
  const result = spawnSync('node', [REVIEW, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const review = JSON.parse(result.stdout);
  assert.ok(typeof review.executiveSummary     === 'string');
  assert.ok(typeof review.bottleneckExplanation === 'string');
  assert.ok(Array.isArray(review.risks));
});

test('cli review: exits 0 with default provider selection', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const inPath = writeTmp(dir, 'report.json', singleReport);
  const env = { ...process.env };
  delete env['PERF_AI_PROVIDER'];
  const result = spawnSync('node', [REVIEW, inPath], { encoding: 'utf-8', env });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const review = JSON.parse(result.stdout);
  assert.ok(typeof review.executiveSummary === 'string');
});

test('cli review: exits 1 when PERF_AI_PROVIDER is unsupported', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const inPath = writeTmp(dir, 'report.json', singleReport);
  const result = spawnSync('node', [REVIEW, inPath], {
    encoding: 'utf-8',
    env: { ...process.env, PERF_AI_PROVIDER: 'openai' },
  });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Unsupported PERF_AI_PROVIDER: openai'));
});

test('cli review: exits 0 and prints AIReview JSON for a PerformanceComparisonReport file', () => {
  const dir    = mkdtempSync(join(tmpdir(), 'perf-'));
  const inPath = writeTmp(dir, 'comparison.json', comparisonReport);
  const result = spawnSync('node', [REVIEW, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 0, `stderr: ${result.stderr}`);
  const review = JSON.parse(result.stdout);
  assert.ok(typeof review.executiveSummary === 'string');
  assert.ok(Array.isArray(review.suggestedNextActions));
});

test('cli review: exits 1 for invalid PerformanceReport file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-'));
  const invalidReport = {
    ...singleReport,
    summary: { p95_latency: 'slow', error_rate: 0.01, throughput: 400 },
    evidence: 'not an array',
  };
  const inPath = writeTmp(dir, 'invalid-report.json', invalidReport);
  const result = spawnSync('node', [REVIEW, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Invalid PerformanceReport'));
  assert.ok(result.stderr.includes('summary.p95_latency must be a number'));
  assert.ok(result.stderr.includes('evidence must be an array'));
});

test('cli review: exits 1 for invalid PerformanceComparisonReport file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'perf-'));
  const invalidReport = {
    ...comparisonReport,
    baseline_summary: { p95_latency: 600, error_rate: 'bad', throughput: 400 },
    improvement: {
      latency_change_percent: -33.3,
      error_change_percent: 'bad',
      throughput_change_percent: null,
    },
  };
  const inPath = writeTmp(dir, 'invalid-comparison.json', invalidReport);
  const result = spawnSync('node', [REVIEW, inPath], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Invalid PerformanceComparisonReport'));
  assert.ok(result.stderr.includes('baseline_summary.error_rate must be a number'));
  assert.ok(result.stderr.includes('improvement.error_change_percent must be a number or null'));
});

test('cli review: exits 1 and prints usage when no argument given', () => {
  const result = spawnSync('node', [REVIEW], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.includes('Usage'));
});

test('cli review: exits 1 when file does not exist', () => {
  const result = spawnSync('node', [REVIEW, '/nonexistent/review.json'], { encoding: 'utf-8' });
  assert.equal(result.status, 1);
  assert.ok(result.stderr.length > 0);
});
