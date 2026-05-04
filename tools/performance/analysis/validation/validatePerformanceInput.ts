function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isNumber(v: unknown): v is number {
  return typeof v === 'number' && !isNaN(v);
}

function inRange(v: unknown, min: number, max: number): boolean {
  return isNumber(v) && v >= min && v <= max;
}

/**
 * Validates a PerformanceInput object received from an external source.
 * Returns an array of human-readable error strings; empty means valid.
 * TypeScript types alone are not sufficient because input arrives as JSON.
 */
export function validatePerformanceInput(input: unknown): string[] {
  const errors: string[] = [];

  if (!isObject(input)) {
    errors.push('input must be a non-null object');
    return errors;
  }

  const required = ['metadata', 'load_profile', 'latency', 'throughput', 'errors', 'system'] as const;
  for (const field of required) {
    if (input[field] === undefined || input[field] === null) {
      errors.push(`missing required field: ${field}`);
    }
  }

  if (errors.length > 0) return errors;

  const latency = input['latency']    as Record<string, unknown>;
  const tput    = input['throughput'] as Record<string, unknown>;
  const errs    = input['errors']     as Record<string, unknown>;
  const system  = input['system']     as Record<string, unknown>;

  if (!isNumber(latency['p50'])) errors.push('latency.p50 must be a number');
  if (!isNumber(latency['p95'])) errors.push('latency.p95 must be a number');
  if (!isNumber(latency['p99'])) errors.push('latency.p99 must be a number');

  if (!isNumber(tput['requests_per_second'])) {
    errors.push('throughput.requests_per_second must be a number');
  }

  if (!inRange(errs['error_rate'], 0, 1)) {
    errors.push('errors.error_rate must be a number between 0 and 1');
  }

  if (!isNumber(errs['total_errors'])) {
    errors.push('errors.total_errors must be a number');
  }

  if (!inRange(system['cpu_usage_percent'], 0, 100)) {
    errors.push('system.cpu_usage_percent must be a number between 0 and 100');
  }

  if (!inRange(system['memory_usage_percent'], 0, 100)) {
    errors.push('system.memory_usage_percent must be a number between 0 and 100');
  }

  const optional = input['optional'];
  if (isObject(optional) && optional['db_pool_usage_percent'] !== undefined) {
    if (!inRange(optional['db_pool_usage_percent'], 0, 100)) {
      errors.push('optional.db_pool_usage_percent must be a number between 0 and 100');
    }
  }

  return errors;
}
