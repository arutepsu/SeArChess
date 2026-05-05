import type { AIReviewProvider } from './aiReviewModels';
import { StubAIReviewProvider } from './aiReviewProvider';

export function createAIReviewProvider(env: NodeJS.ProcessEnv = process.env): AIReviewProvider {
  const provider = env['PERF_AI_PROVIDER'];
  if (provider === undefined || provider === 'stub') {
    return new StubAIReviewProvider();
  }
  throw new Error(`Unsupported PERF_AI_PROVIDER: ${provider}`);
}
