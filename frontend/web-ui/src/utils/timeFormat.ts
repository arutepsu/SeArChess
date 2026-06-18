/** Format a millisecond count as M:SS (e.g. 90 000 ms → "1:30"). */
export function formatClockMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

/** Format a whole-second count as M:SS (e.g. 90 s → "1:30"). */
export function formatClockSeconds(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}
