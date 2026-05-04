import type { NormalizerContext } from './normalizerModels';

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && !Number.isNaN(value);
}

function hasString(value: unknown): boolean {
  return typeof value === 'string' && value.length > 0;
}

function inPercentRange(value: unknown): boolean {
  return isNumber(value) && value >= 0 && value <= 100;
}

export function validateNormalizerContext(context: unknown): string[] {
  const errors: string[] = [];

  if (!isObject(context)) {
    errors.push('context must be a non-null object');
    return errors;
  }

  if (!hasString(context['testType'])) errors.push('context.testType is required');
  if (!hasString(context['scenarioName'])) errors.push('context.scenarioName is required');
  if (!isNumber(context['maxUsers'])) errors.push('context.maxUsers must be a number');
  if (!hasString(context['duration'])) errors.push('context.duration is required');
  if (!hasString(context['rampUpPattern'])) errors.push('context.rampUpPattern is required');

  if (!inPercentRange(context['cpuUsagePercent'])) {
    errors.push('context.cpuUsagePercent must be a number between 0 and 100');
  }
  if (!inPercentRange(context['memoryUsagePercent'])) {
    errors.push('context.memoryUsagePercent must be a number between 0 and 100');
  }

  if (context['dbPoolUsagePercent'] !== undefined && !inPercentRange(context['dbPoolUsagePercent'])) {
    errors.push('context.dbPoolUsagePercent must be a number between 0 and 100');
  }

  return errors;
}

export function assertValidNormalizerContext(context: unknown): asserts context is NormalizerContext {
  const errors = validateNormalizerContext(context);
  if (errors.length > 0) {
    throw new Error(`Invalid NormalizerContext: ${errors.join('; ')}`);
  }
}
