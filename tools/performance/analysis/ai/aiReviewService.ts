import type { AIReviewRequest, AIReview, AIReviewProvider } from './aiReviewModels';
import { buildPrompt } from './aiPromptBuilder';
import { validateAIReview } from '../validation/validateAIReview';

export async function runAIReview(
  request: AIReviewRequest,
  provider: AIReviewProvider,
): Promise<AIReview> {
  const prompt = buildPrompt(request);
  const review = await provider.review(prompt);
  const errors = validateAIReview(review);
  if (errors.length > 0) {
    throw new Error(`Invalid AIReview provider output:\n${errors.map((e) => `- ${e}`).join('\n')}`);
  }
  return review;
}
