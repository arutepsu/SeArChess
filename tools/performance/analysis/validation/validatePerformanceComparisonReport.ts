import type { PerformanceComparisonReport } from '../domain/models';

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isNumber(v: unknown): v is number {
  return typeof v === 'number' && !Number.isNaN(v);
}

function hasField(obj: Record<string, unknown>, field: string): boolean {
  return obj[field] !== undefined && obj[field] !== null;
}

function validateSummary(summary: unknown, name: string, errors: string[]): void {
  if (!isObject(summary)) {
    errors.push(`${name} must be an object`);
    return;
  }
  if (!isNumber(summary['p95_latency'])) errors.push(`${name}.p95_latency must be a number`);
  if (!isNumber(summary['error_rate'])) errors.push(`${name}.error_rate must be a number`);
  if (!isNumber(summary['throughput'])) errors.push(`${name}.throughput must be a number`);
}

function isNumberOrNull(v: unknown): boolean {
  return v === null || isNumber(v);
}

export function validatePerformanceComparisonReport(input: unknown): string[] {
  const errors: string[] = [];

  if (!isObject(input)) {
    errors.push('comparison report must be a non-null object');
    return errors;
  }

  const required = ['baseline_summary', 'optimized_summary', 'improvement', 'interpretation', 'verdict'] as const;
  for (const field of required) {
    if (!hasField(input, field)) {
      errors.push(`missing required field: ${field}`);
    }
  }

  if (hasField(input, 'baseline_summary')) {
    validateSummary(input['baseline_summary'], 'baseline_summary', errors);
  }
  if (hasField(input, 'optimized_summary')) {
    validateSummary(input['optimized_summary'], 'optimized_summary', errors);
  }

  const improvement = input['improvement'];
  if (isObject(improvement)) {
    const fields = ['latency_change_percent', 'error_change_percent', 'throughput_change_percent'] as const;
    for (const field of fields) {
      if (!(field in improvement)) {
        errors.push(`improvement.${field} is required`);
      } else if (!isNumberOrNull(improvement[field])) {
        errors.push(`improvement.${field} must be a number or null`);
      }
    }
  } else if (hasField(input, 'improvement')) {
    errors.push('improvement must be an object');
  }

  if (hasField(input, 'interpretation') && !Array.isArray(input['interpretation'])) {
    errors.push('interpretation must be an array');
  }
  if (!hasField(input, 'verdict')) {
    errors.push('verdict is required');
  }

  return errors;
}

export function isValidPerformanceComparisonReport(input: unknown): input is PerformanceComparisonReport {
  return validatePerformanceComparisonReport(input).length === 0;
}
