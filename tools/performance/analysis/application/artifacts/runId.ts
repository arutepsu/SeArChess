import { randomBytes } from 'node:crypto';

function safeTimestamp(date = new Date()): string {
  return date.toISOString()
    .replace(/\.\d{3}Z$/, '')
    .replace(/[-:]/g, '');
}

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

export function createRunId(kind: string): string {
  const shortId = randomBytes(3).toString('hex');
  return `${safeTimestamp()}-${slug(kind)}-${shortId}`;
}
