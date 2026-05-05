import type { PerformanceReport, PerformanceComparisonReport } from '../domain/models';
import { buildComparisonReport } from '../reporting/comparisonReportBuilder';

export function compare(
  baseline: PerformanceReport,
  optimized: PerformanceReport,
): PerformanceComparisonReport {
  return buildComparisonReport(baseline, optimized);
}
