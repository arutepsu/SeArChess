import type { Verdict } from './models';
import { HIGH_ERROR_RATE_THRESHOLD, SIGNIFICANCE_THRESHOLD_PERCENT } from './thresholds';

/**
 * Returns the percentage change from baseline to optimized, or null when
 * baseline is zero (percentage change is mathematically undefined).
 */
export function percentChange(baseline: number, optimized: number): number | null {
  if (baseline === 0) return null;
  return parseFloat((((optimized - baseline) / baseline) * 100).toFixed(2));
}

function isSignificantlyWorse(change: number | null): boolean {
  return change !== null && change > SIGNIFICANCE_THRESHOLD_PERCENT;
}

function isSignificantlyBetter(change: number | null): boolean {
  return change !== null && change < -SIGNIFICANCE_THRESHOLD_PERCENT;
}

/**
 * Determines a comparison verdict using percentage changes and an absolute
 * zero-baseline guard for error rate.
 */
export function determineVerdict(
  latencyChange: number | null,
  errorChange: number | null,
  baselineErrorRate: number,
  optimizedErrorRate: number,
): Verdict {
  // Absolute check: baseline was zero but the optimized run crossed the high-error threshold.
  // percentChange(0, x) is null, so the significance check would never fire without this guard.
  const errorAbsoluteRegression =
    baselineErrorRate === 0 && optimizedErrorRate > HIGH_ERROR_RATE_THRESHOLD;

  if (isSignificantlyWorse(latencyChange) || isSignificantlyWorse(errorChange) || errorAbsoluteRegression) {
    return 'REGRESSION';
  }

  if (isSignificantlyBetter(latencyChange) && !isSignificantlyWorse(errorChange) && !errorAbsoluteRegression) {
    return 'SUCCESS';
  }

  return 'NO_CHANGE';
}
