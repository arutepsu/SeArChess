import type { ReviewFinding, ReviewReport } from '../ai/aiReviewModels';

function findingTitle(finding: ReviewFinding, index: number): string {
  const location = finding.location ? ` (${finding.location})` : '';
  return `${index + 1}. ${finding.severity} / ${finding.category}${location}`;
}

export function renderStructuredReviewMarkdown(report: ReviewReport): string {
  const lines: string[] = [
    '# Structured Review Report',
    '',
    '## Summary',
    '',
    report.summary,
    '',
    '## Findings',
    '',
  ];

  if (report.findings.length === 0) {
    lines.push('No findings.', '');
  } else {
    report.findings.forEach((finding, index) => {
      lines.push(`### ${findingTitle(finding, index)}`);
      lines.push('');
      lines.push(`- Message: ${finding.message}`);
      lines.push(`- Reasoning: ${finding.reasoning}`);
      lines.push(`- Suggestion: ${finding.suggestion}`);
      lines.push('');
    });
  }

  lines.push('## Suggested Next Steps', '');
  if (report.suggestedNextSteps.length === 0) {
    lines.push('No suggested next steps.', '');
  } else {
    report.suggestedNextSteps.forEach((step) => lines.push(`- ${step}`));
    lines.push('');
  }

  return lines.join('\n');
}
