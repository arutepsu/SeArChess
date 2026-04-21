import type { SessionResponse } from "../api/backendTypes";

export type SessionContext = SessionResponse;

const STORAGE_KEY = "searchess.activeSession";

function isSessionContext(value: unknown): value is SessionContext {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.sessionId === "string" &&
    typeof candidate.gameId === "string" &&
    typeof candidate.mode === "string" &&
    typeof candidate.lifecycle === "string" &&
    typeof candidate.whiteController === "string" &&
    typeof candidate.blackController === "string" &&
    typeof candidate.createdAt === "string" &&
    typeof candidate.updatedAt === "string"
  );
}

export function loadStoredSession(): SessionContext | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as unknown;
    if (isSessionContext(parsed)) return parsed;
    window.localStorage.removeItem(STORAGE_KEY);
    return null;
  } catch {
    try {
      window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Ignore storage cleanup failures.
    }
    return null;
  }
}

export function persistSession(session: SessionContext | null): void {
  if (typeof window === "undefined") return;
  try {
    if (session) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Storage can fail in private browsing or locked-down browser contexts.
  }
}
