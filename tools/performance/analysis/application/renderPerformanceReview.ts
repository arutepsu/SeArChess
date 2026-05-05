import {
  renderMarkdownReport,
  type MarkdownReportInput,
} from '../reporting/markdownReportBuilder';

export function renderPerformanceReview(input: MarkdownReportInput): string {
  return renderMarkdownReport(input);
}
