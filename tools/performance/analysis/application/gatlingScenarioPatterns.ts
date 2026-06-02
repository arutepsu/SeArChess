export type GatlingScenarioPatternId =
  | 'all'
  | 'gameplay'
  | 'session'
  | 'legalMoves'
  | 'moveSubmission'
  | 'readHeavy'
  | 'writeHeavy';

export interface GatlingScenarioPattern {
  id: GatlingScenarioPatternId;
  label: string;
  description: string;
}

const PATTERNS: readonly GatlingScenarioPattern[] = [
  { id: 'all', label: 'All simulations', description: 'Run the full Gatling suite.' },
  { id: 'gameplay', label: 'Gameplay flow', description: 'Create session, fetch legal moves, submit moves, fetch updated state.' },
  { id: 'session', label: 'Session creation', description: 'Only session creation endpoint.' },
  { id: 'legalMoves', label: 'Legal moves', description: 'Create session, then repeatedly fetch legal moves.' },
  { id: 'moveSubmission', label: 'Move submission', description: 'Create session, fetch legal moves, submit moves.' },
  { id: 'readHeavy', label: 'Read-heavy API', description: 'Mostly read operations such as legal moves and state.' },
  { id: 'writeHeavy', label: 'Write-heavy API', description: 'Mostly move submission operations.' },
];

export const DEFAULT_GATLING_SCENARIO_PATTERN_ID: GatlingScenarioPatternId = 'gameplay';

export function listGatlingScenarioPatterns(): readonly GatlingScenarioPattern[] {
  return PATTERNS;
}

export function findGatlingScenarioPattern(id: GatlingScenarioPatternId): GatlingScenarioPattern {
  const pattern = PATTERNS.find((candidate) => candidate.id === id);
  if (!pattern) throw new Error(`Unknown Gatling pattern: ${id}. Supported: ${PATTERNS.map((p) => p.id).join(', ')}.`);
  return pattern;
}

export function isGatlingScenarioPatternId(value: string): value is GatlingScenarioPatternId {
  return PATTERNS.some((pattern) => pattern.id === value);
}
