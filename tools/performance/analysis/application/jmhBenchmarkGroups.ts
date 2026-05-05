export type JmhBenchmarkGroupId =
  | 'all'
  | 'domain-rules'
  | 'move-application'
  | 'game-service'
  | 'mapping'
  | 'json-rendering'
  | 'response-construction'
  | 'custom';

export interface JmhBenchmarkGroup {
  id: JmhBenchmarkGroupId;
  label: string;
  description: string;
  pattern: string | null;
}

const GROUPS: readonly JmhBenchmarkGroup[] = [
  {
    id: 'all',
    label: 'All benchmarks',
    description: 'Run the full JMH suite.',
    pattern: 'chess.benchmarks.*',
  },
  {
    id: 'domain-rules',
    label: 'Domain rules',
    description: 'Pure chess rule/move-generation cost.',
    pattern: 'chess.benchmarks.LegalMoveGenerationBenchmark.*',
  },
  {
    id: 'move-application',
    label: 'Move application',
    description: 'Pure state transition and move-application cost.',
    pattern: 'chess.benchmarks.MoveApplicationBenchmark.*',
  },
  {
    id: 'game-service',
    label: 'Game service',
    description: 'In-memory application-service boundary cost.',
    pattern: 'chess.benchmarks.GameServiceBenchmark.*',
  },
  {
    id: 'mapping',
    label: 'Mapping',
    description: 'Domain/application model to DTO mapping cost.',
    pattern: 'chess.benchmarks.MappingBenchmark.*',
  },
  {
    id: 'json-rendering',
    label: 'JSON rendering',
    description: 'DTO to JSON rendering/serialization cost.',
    pattern: 'chess.benchmarks.JsonRenderingBenchmark.*',
  },
  {
    id: 'response-construction',
    label: 'Response construction',
    description: 'Mapping plus JSON rendering layer.',
    pattern: 'chess.benchmarks.*(MappingBenchmark|JsonRenderingBenchmark).*',
  },
  {
    id: 'custom',
    label: 'Custom pattern',
    description: 'Advanced JMH pattern/regex.',
    pattern: null,
  },
];

export const DEFAULT_JMH_GROUP_ID: JmhBenchmarkGroupId = 'all';

export function listJmhBenchmarkGroups(): readonly JmhBenchmarkGroup[] {
  return GROUPS;
}

export function findJmhBenchmarkGroup(id: JmhBenchmarkGroupId): JmhBenchmarkGroup {
  const group = GROUPS.find((g) => g.id === id);
  if (!group) throw new Error(`Unknown JMH benchmark group: ${id}`);
  return group;
}

export function resolveJmhPattern(groupId: JmhBenchmarkGroupId, customPattern?: string): string {
  if (groupId === 'custom') {
    if (!customPattern || customPattern.trim().length === 0) {
      throw new Error('Custom benchmark group requires a pattern');
    }
    return customPattern;
  }
  const group = findJmhBenchmarkGroup(groupId);
  if (group.pattern === null) throw new Error(`Benchmark group ${groupId} has no pattern`);
  return group.pattern;
}

export function isValidNamedGroupId(id: string): id is Exclude<JmhBenchmarkGroupId, 'custom'> {
  return GROUPS.some((g) => g.id === id && g.id !== 'custom');
}
