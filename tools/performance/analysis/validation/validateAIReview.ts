import type { AIReview } from '../ai/aiReviewModels';

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isStringArray(v: unknown): boolean {
  return Array.isArray(v) && v.every((item) => typeof item === 'string');
}

export function validateAIReview(input: unknown): string[] {
  const errors: string[] = [];

  if (!isObject(input)) {
    errors.push('AIReview must be a non-null object');
    return errors;
  }

  const stringFields = [
    'executiveSummary',
    'bottleneckExplanation',
    'improvementAssessment',
    'confidenceCommentary',
  ] as const;
  for (const field of stringFields) {
    if (typeof input[field] !== 'string') {
      errors.push(`${field} must be a string`);
    }
  }

  const arrayFields = ['risks', 'suggestedNextActions', 'missingEvidence'] as const;
  for (const field of arrayFields) {
    if (!isStringArray(input[field])) {
      errors.push(`${field} must be an array of strings`);
    }
  }

  return errors;
}

export function isValidAIReview(input: unknown): input is AIReview {
  return validateAIReview(input).length === 0;
}
