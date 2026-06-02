import type { ReviewCategory, ReviewReport, ReviewSeverity } from '../ai/aiReviewModels';

const severities: readonly ReviewSeverity[] = ['info', 'warning', 'critical'];
const categories: readonly ReviewCategory[] = [
  'architecture',
  'testing',
  'performance',
  'coupling',
  'maintainability',
  'unknown',
];

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isStringArray(v: unknown): boolean {
  return Array.isArray(v) && v.every((item) => typeof item === 'string');
}

function isSeverity(v: unknown): v is ReviewSeverity {
  return typeof v === 'string' && severities.includes(v as ReviewSeverity);
}

function isCategory(v: unknown): v is ReviewCategory {
  return typeof v === 'string' && categories.includes(v as ReviewCategory);
}

export function validateReviewInput(input: unknown): string[] {
  const errors: string[] = [];
  if (!isObject(input)) {
    errors.push('ReviewInput must be a non-null object');
    return errors;
  }

  const optionalStrings = ['moduleName', 'userQuestion', 'reviewText'] as const;
  for (const field of optionalStrings) {
    if (input[field] !== undefined && typeof input[field] !== 'string') {
      errors.push(`${field} must be a string when provided`);
    }
  }

  if (input['notes'] !== undefined && !isStringArray(input['notes'])) {
    errors.push('notes must be an array of strings when provided');
  }

  return errors;
}

export function isValidReviewInput(input: unknown): input is import('../ai/aiReviewModels').ReviewInput {
  return validateReviewInput(input).length === 0;
}

export function validateReviewReport(input: unknown): string[] {
  const errors: string[] = [];

  if (!isObject(input)) {
    errors.push('ReviewReport must be a non-null object');
    return errors;
  }

  if (typeof input['summary'] !== 'string') {
    errors.push('summary must be a string');
  }

  if (!Array.isArray(input['findings'])) {
    errors.push('findings must be an array');
  } else {
    input['findings'].forEach((finding, index) => {
      if (!isObject(finding)) {
        errors.push(`findings[${index}] must be an object`);
        return;
      }
      if (!isSeverity(finding['severity'])) {
        errors.push(`findings[${index}].severity must be one of: ${severities.join(', ')}`);
      }
      if (!isCategory(finding['category'])) {
        errors.push(`findings[${index}].category must be one of: ${categories.join(', ')}`);
      }
      if (finding['location'] !== undefined && typeof finding['location'] !== 'string') {
        errors.push(`findings[${index}].location must be a string when provided`);
      }
      for (const field of ['message', 'reasoning', 'suggestion'] as const) {
        if (typeof finding[field] !== 'string') {
          errors.push(`findings[${index}].${field} must be a string`);
        }
      }
    });
  }

  if (!isStringArray(input['suggestedNextSteps'])) {
    errors.push('suggestedNextSteps must be an array of strings');
  }

  return errors;
}

export function isValidReviewReport(input: unknown): input is ReviewReport {
  return validateReviewReport(input).length === 0;
}
