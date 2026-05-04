import { join } from 'node:path';
import { resolveArtifactRoot, resolveRepoRoot } from './artifactRoot';
import { createRunId } from './runId';

export function createInteractiveRunOutDir(
  phase: 'baseline' | 'optimized',
  tool: string,
  testOrSuite: string,
  outputRoot?: string,
): string {
  const base = outputRoot
    ? resolveArtifactRoot({ outputRoot })
    : join(resolveRepoRoot(), 'docs', 'performance', phase);
  const runId = createRunId(`${tool}-${testOrSuite}`);
  return join(base, 'runs', runId);
}
