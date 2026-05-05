import type { PerformanceReport, PerformanceComparisonReport } from '../domain/models';
import { percentChange, determineVerdict } from '../domain/comparisonPolicy';

function buildInterpretation(
  latencyChange: number | null,
  errorChange: number | null,
  throughputChange: number | null,
  baselineErrorRate: number,
  optimizedErrorRate: number,
): string[] {
  const lines: string[] = [];

  if (latencyChange !== null) {
    const dir = latencyChange < 0 ? 'improved' : latencyChange > 0 ? 'degraded' : 'unchanged';
    lines.push(`p95 latency ${dir} by ${Math.abs(latencyChange).toFixed(1)}%`);
  }

  if (errorChange !== null) {
    const dir = errorChange < 0 ? 'improved' : errorChange > 0 ? 'degraded' : 'unchanged';
    lines.push(`error rate ${dir} by ${Math.abs(errorChange).toFixed(1)}%`);
  } else if (baselineErrorRate === 0 && optimizedErrorRate > 0) {
    // Percent change is undefined when baseline is 0; describe the absolute move instead.
    lines.push(`error rate increased from 0 to ${optimizedErrorRate} (absolute increase)`);
  }

  if (throughputChange !== null) {
    const dir = throughputChange > 0 ? 'improved' : throughputChange < 0 ? 'degraded' : 'unchanged';
    lines.push(`throughput ${dir} by ${Math.abs(throughputChange).toFixed(1)}%`);
  }

  return lines;
}

export function buildComparisonReport(
  baseline: PerformanceReport,
  optimized: PerformanceReport,
): PerformanceComparisonReport {
  const latencyChange    = percentChange(baseline.summary.p95_latency, optimized.summary.p95_latency);
  const errorChange      = percentChange(baseline.summary.error_rate,  optimized.summary.error_rate);
  const throughputChange = percentChange(baseline.summary.throughput,   optimized.summary.throughput);

  const baselineErrorRate  = baseline.summary.error_rate;
  const optimizedErrorRate = optimized.summary.error_rate;

  return {
    baseline_summary:  baseline.summary,
    optimized_summary: optimized.summary,
    improvement: {
      latency_change_percent:    latencyChange,
      error_change_percent:      errorChange,
      throughput_change_percent: throughputChange,
    },
    interpretation: buildInterpretation(
      latencyChange, errorChange, throughputChange,
      baselineErrorRate, optimizedErrorRate,
    ),
    verdict: determineVerdict(latencyChange, errorChange, baselineErrorRate, optimizedErrorRate),
  };
}
