import type { PerformanceReport } from '../domain/models';

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isNumber(v: unknown): v is number {
  return typeof v === 'number' && !Number.isNaN(v);
}

function hasField(obj: Record<string, unknown>, field: string): boolean {
  return obj[field] !== undefined && obj[field] !== null;
}

export function validatePerformanceReport(input: unknown): string[] {
  const errors: string[] = [];

  if (!isObject(input)) {
    errors.push('report must be a non-null object');
    return errors;
  }

  const required = ['metadata', 'summary', 'observations', 'bottleneck', 'evidence', 'suggestions', 'notes'] as const;
  for (const field of required) {
    if (!hasField(input, field)) {
      errors.push(`missing required field: ${field}`);
    }
  }

  const summary = input['summary'];
  if (isObject(summary)) {
    if (!isNumber(summary['p95_latency'])) errors.push('summary.p95_latency must be a number');
    if (!isNumber(summary['error_rate'])) errors.push('summary.error_rate must be a number');
    if (!isNumber(summary['throughput'])) errors.push('summary.throughput must be a number');
  } else if (hasField(input, 'summary')) {
    errors.push('summary must be an object');
  }

  const bottleneck = input['bottleneck'];
  if (isObject(bottleneck)) {
    if (!hasField(bottleneck, 'type')) errors.push('bottleneck.type is required');
    if (!hasField(bottleneck, 'confidence')) errors.push('bottleneck.confidence is required');
  } else if (hasField(input, 'bottleneck')) {
    errors.push('bottleneck must be an object');
  }

  if (hasField(input, 'observations') && !Array.isArray(input['observations'])) {
    errors.push('observations must be an array');
  }
  if (hasField(input, 'evidence') && !Array.isArray(input['evidence'])) {
    errors.push('evidence must be an array');
  }
  if (hasField(input, 'suggestions') && !Array.isArray(input['suggestions'])) {
    errors.push('suggestions must be an array');
  }
  if (hasField(input, 'notes') && !Array.isArray(input['notes'])) {
    errors.push('notes must be an array');
  }

  return errors;
}

export function isValidPerformanceReport(input: unknown): input is PerformanceReport {
  return validatePerformanceReport(input).length === 0;
}
