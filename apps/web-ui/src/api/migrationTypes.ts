export type MigrationBackend = "postgres" | "mongo";
export type MigrationMode = "dry-run" | "execute" | "validate-only";

export interface MigrationRequest {
  source: MigrationBackend;
  target: MigrationBackend;
  mode: MigrationMode;
  batchSize: number;
  validateAfterExecute: boolean;
  confirmation?: string;
}

export interface MigrationItemResult {
  type: string;
  sessionId: string;
  gameId: string;
  reason?: string;
  phase?: string;
  message?: string;
}

/** Matches the JSON shape produced by MigrationReportFormatter.formatJson on the backend. */
export interface MigrationReport {
  runId: string;
  mode: string;
  source: string;
  target: string;
  status: string;
  startedAt: string;
  finishedAt: string;
  duration: string;
  batchSize: number;
  batchCount: number;
  scanned: number;
  scannedSessions: number;
  migrated: number;
  wouldMigrate: number;
  skippedEquivalent: number;
  conflicts: number;
  failed: number;
  validationRan: boolean;
  validationResult: string | null;
  validationMismatches: number;
  validatedEquivalent: number;
  sourceDataMissing: number;
  writeFailures: number;
  readFailures: number;
  fatalFailure: string | null;
  itemResultsPreview: MigrationItemResult[];
}
