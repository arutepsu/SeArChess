import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { K6ArtifactPaths, K6OutputMode, K6Phase, K6ReportProgressEvent, K6TestName, RunK6ReportResult } from './runK6Report';
import { runK6Report, runK6ReportAsync } from './runK6Report';
import type { PerformanceReport } from '../domain/models';
import { renderK6SuiteMarkdownReport } from '../reporting/k6SuiteMarkdownBuilder';

export const K6_SUITE_TEST_ORDER: K6TestName[] = ['baseline', 'load', 'spike', 'stress'];
export type K6SuiteProgressStep =
  | 'suite:start'
  | 'suite:test-start'
  | 'suite:test-progress'
  | 'suite:test-complete'
  | 'suite:markdown-written'
  | 'suite:complete';

export interface K6SuiteProgressEvent {
  step: K6SuiteProgressStep;
  test?: K6TestName;
  message: string;
  path?: string;
  reportEvent?: K6ReportProgressEvent;
}

export interface RunK6SuiteOptions {
  baseUrl: string;
  cpu: number;
  memory: number;
  phase: K6Phase;
  out?: string;
  outputMode?: K6OutputMode;
  onProgress?: (event: K6SuiteProgressEvent) => void;
}

export interface RunK6SuiteTestResult {
  test: K6TestName;
  report: PerformanceReport;
  artifactPaths: K6ArtifactPaths;
  k6ExitCode: number | null;
  continuedAfterThresholdFailure: boolean;
}

export interface RunK6SuiteResult {
  results: RunK6SuiteTestResult[];
  suiteReportPath: string;
}

export function buildK6SuiteReportPath(outDir: string): string {
  return join(outDir, 'k6_suite_report.md');
}

export function runK6Suite(options: RunK6SuiteOptions): RunK6SuiteResult {
  options.onProgress?.({
    step: 'suite:start',
    message: 'Starting k6 suite.',
  });
  const results = K6_SUITE_TEST_ORDER.map((test): RunK6SuiteTestResult => {
    options.onProgress?.({
      step: 'suite:test-start',
      test,
      message: `Starting ${test}.`,
    });
    const result: RunK6ReportResult = runK6Report({
      ...options,
      test,
      onProgress: (event) => options.onProgress?.({
        step: 'suite:test-progress',
        test,
        message: event.message,
        path: event.path,
        reportEvent: event,
      }),
    });
    options.onProgress?.({
      step: 'suite:test-complete',
      test,
      message: `${test} report generated.`,
      path: result.artifactPaths.reportJsonPath,
    });
    return {
      test,
      report: result.report,
      artifactPaths: result.artifactPaths,
      k6ExitCode: result.k6ExitCode,
      continuedAfterThresholdFailure: result.continuedAfterThresholdFailure,
    };
  });

  const suiteReportPath = buildK6SuiteReportPath(results[0]?.artifactPaths.outDir ?? '');
  const suiteMarkdown = renderK6SuiteMarkdownReport({ results, suiteReportPath });
  writeFileSync(suiteReportPath, suiteMarkdown);
  options.onProgress?.({
    step: 'suite:markdown-written',
    message: 'Suite Markdown report generated.',
    path: suiteReportPath,
  });
  options.onProgress?.({
    step: 'suite:complete',
    message: 'k6 suite complete.',
    path: suiteReportPath,
  });

  return { results, suiteReportPath };
}

export async function runK6SuiteAsync(options: RunK6SuiteOptions): Promise<RunK6SuiteResult> {
  options.onProgress?.({
    step: 'suite:start',
    message: 'Starting k6 suite.',
  });
  const results: RunK6SuiteTestResult[] = [];

  for (const test of K6_SUITE_TEST_ORDER) {
    options.onProgress?.({
      step: 'suite:test-start',
      test,
      message: `Starting ${test}.`,
    });
    const result: RunK6ReportResult = await runK6ReportAsync({
      ...options,
      test,
      onProgress: (event) => options.onProgress?.({
        step: 'suite:test-progress',
        test,
        message: event.message,
        path: event.path,
        reportEvent: event,
      }),
    });
    options.onProgress?.({
      step: 'suite:test-complete',
      test,
      message: `${test} report generated.`,
      path: result.artifactPaths.reportJsonPath,
    });
    results.push({
      test,
      report: result.report,
      artifactPaths: result.artifactPaths,
      k6ExitCode: result.k6ExitCode,
      continuedAfterThresholdFailure: result.continuedAfterThresholdFailure,
    });
  }

  const suiteReportPath = buildK6SuiteReportPath(results[0]?.artifactPaths.outDir ?? '');
  const suiteMarkdown = renderK6SuiteMarkdownReport({ results, suiteReportPath });
  writeFileSync(suiteReportPath, suiteMarkdown);
  options.onProgress?.({
    step: 'suite:markdown-written',
    message: 'Suite Markdown report generated.',
    path: suiteReportPath,
  });
  options.onProgress?.({
    step: 'suite:complete',
    message: 'k6 suite complete.',
    path: suiteReportPath,
  });

  return { results, suiteReportPath };
}
