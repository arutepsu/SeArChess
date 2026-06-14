import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { GameState, PlayerColor } from "../../api/types";

const baseClockMs = 10 * 60 * 1000;

interface UseGameClockParams {
  gameId: string | undefined;
  gameStatus: GameState["status"] | undefined;
  activeColor: PlayerColor | undefined;
}

export function useGameClock({ gameId, gameStatus, activeColor }: UseGameClockParams) {
  const [whiteClockMs, setWhiteClockMs] = useState(baseClockMs);
  const [blackClockMs, setBlackClockMs] = useState(baseClockMs);
  const lastTickMs = useRef<number | null>(null);

  const clockStateRef = useRef({
    running: false,
    activeColor: "white" as PlayerColor,
  });

  const clockRunning = useMemo(() => {
    return gameStatus === "active" || gameStatus === "check";
  }, [gameStatus]);

  const resetClocks = useCallback(() => {
    setWhiteClockMs(baseClockMs);
    setBlackClockMs(baseClockMs);
    lastTickMs.current = performance.now();
  }, []);

  useEffect(() => {
    if (gameId) {
      resetClocks();
    }
  }, [gameId, resetClocks]);

  // Sync ref on every render so the interval callback never reads stale values.
  useEffect(() => {
    clockStateRef.current = {
      running: clockRunning,
      activeColor: activeColor ?? "white",
    };
  }, [clockRunning, activeColor]);

  useEffect(() => {
    lastTickMs.current = performance.now();

    const intervalId = window.setInterval(() => {
      const now = performance.now();
      const last = lastTickMs.current ?? now;
      lastTickMs.current = now;
      const { running, activeColor: tickColor } = clockStateRef.current;

      if (!running) return;
      const delta = Math.max(0, now - last);

      if (tickColor === "white") {
        setWhiteClockMs((v) => Math.max(0, v - delta));
      } else {
        setBlackClockMs((v) => Math.max(0, v - delta));
      }
    }, 250);

    return () => window.clearInterval(intervalId);
  }, []);

  return { whiteClockMs, blackClockMs, clockRunning };
}
