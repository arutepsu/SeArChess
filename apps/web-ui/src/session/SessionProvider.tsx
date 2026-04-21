import { createContext, useCallback, useContext, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { SessionContext } from "./sessionStore";

type SessionStore = {
  session: SessionContext | null;
  setSession: (s: SessionContext | null) => void;
  getSessionId: () => string | null;
  getGameId: () => string | null;
};

const SessionCtx = createContext<SessionStore | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<SessionContext | null>(null);
  const sessionRef = useRef<SessionContext | null>(null);

  const setSession = useCallback((s: SessionContext | null) => {
    sessionRef.current = s;
    setSessionState(s);
  }, []);

  const getSessionId = useCallback((): string | null => {
    return sessionRef.current?.sessionId ?? null;
  }, []);

  const getGameId = useCallback((): string | null => {
    return sessionRef.current?.gameId ?? null;
  }, []);

  return (
    <SessionCtx.Provider value={{ session, setSession, getSessionId, getGameId }}>
      {children}
    </SessionCtx.Provider>
  );
}

export function useSession(): SessionStore {
  const ctx = useContext(SessionCtx);
  if (!ctx) throw new Error("useSession must be used inside <SessionProvider>");
  return ctx;
}
