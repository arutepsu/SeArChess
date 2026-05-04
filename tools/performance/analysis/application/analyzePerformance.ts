import type { PerformanceInput, PerformanceReport } from '../domain/models';
import { validatePerformanceInput } from '../validation/validatePerformanceInput';
import { classifyBottleneck } from '../domain/bottleneckRules';
import { buildReport } from '../reporting/reportBuilder';

export function analyze(input: unknown): PerformanceReport {
  const errors = validatePerformanceInput(input);
  if (errors.length > 0) {
    throw new Error(`Invalid PerformanceInput: ${errors.join('; ')}`);
  }

  const validated = input as PerformanceInput;
  const bottleneck = classifyBottleneck(validated);
  return buildReport(validated, bottleneck);
}
