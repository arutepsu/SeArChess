import type { PerformanceInput, PerformanceReport, Bottleneck } from '../domain/models';
import { buildObservations } from './observationBuilder';
import { buildEvidence } from './evidenceBuilder';
import { SUGGESTIONS } from './suggestionCatalog';

function buildNotes(input: PerformanceInput): string[] {
  const notes: string[] = [];

  if (input.optional === undefined || input.optional.db_pool_usage_percent === undefined) {
    notes.push('db_pool_usage_percent not provided; database connection pressure is unknown');
  }

  return notes;
}

export function buildReport(input: PerformanceInput, bottleneck: Bottleneck): PerformanceReport {
  return {
    metadata: input.metadata,
    summary: {
      p95_latency: input.latency.p95,
      error_rate:  input.errors.error_rate,
      throughput:  input.throughput.requests_per_second,
    },
    observations: buildObservations(input),
    bottleneck,
    evidence:    buildEvidence(input, bottleneck.type),
    suggestions: SUGGESTIONS[bottleneck.type],
    notes:       buildNotes(input),
  };
}
