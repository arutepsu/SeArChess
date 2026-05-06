import type {
  AIReviewRequest,
  AIReview,
  AIReviewProvider,
  ReviewInput,
  ReviewReader,
  ReviewReport,
} from './aiReviewModels';
import { buildPrompt } from './aiPromptBuilder';
import { validateAIReview } from '../validation/validateAIReview';
import { validateReviewReport } from '../validation/validateReviewReport';

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

export async function readReviewInput(
  input: ReviewInput,
  reader: ReviewReader,
): Promise<ReviewReport> {
  const report = await reader.readReview(input);
  const errors = validateReviewReport(report);
  if (errors.length > 0) {
    throw new Error(`Invalid ReviewReport reader output:\n${errors.map((e) => `- ${e}`).join('\n')}`);
  }
  return report;
}
