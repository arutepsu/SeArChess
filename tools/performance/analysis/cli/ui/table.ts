import Table from 'cli-table3';

const PLAIN_TABLE_CHARS = {
  top: '',
  'top-mid': '',
  'top-left': '',
  'top-right': '',
  bottom: '',
  'bottom-mid': '',
  'bottom-left': '',
  'bottom-right': '',
  left: '',
  'left-mid': '',
  mid: '',
  'mid-mid': '',
  right: '',
  'right-mid': '',
  middle: ' ',
} as const;

export function renderTable(headers: string[], rows: string[][]): string {
  const table = new Table({
    head: headers,
    chars: PLAIN_TABLE_CHARS,
    style: {
      head: [],
      border: [],
      compact: true,
    },
  });
  rows.forEach((row) => table.push(row));
  return table.toString();
}
