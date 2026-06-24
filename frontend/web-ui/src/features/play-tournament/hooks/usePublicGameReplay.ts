import { useCallback, useEffect, useReducer, useRef } from "react";
import { Chess } from "chess.js";
import type { Square, ShortMove } from "chess.js";
import type { PublicGameSnapshot, PublicTournamentEvent } from "../../../api/publicTournamentTypes";
import { applyUciMove, STANDARD_OPENING_FEN } from "../utils/fenToBoardMatrix";

export interface PublicGameReplayFrame {
  index: number;
  fen: string;
  moveUci?: string;
  sourceEventType?: string;
}

interface ReplayState {
  frames: PublicGameReplayFrame[];
  currentIndex: number;
  isPlaying: boolean;
  reconstructedFromStandard: boolean;
}

type ReplayAction =
  | { type: "loadFrames"; frames: PublicGameReplayFrame[]; reconstructedFromStandard: boolean }
  | { type: "addFrame"; frame: Omit<PublicGameReplayFrame, "index"> }
  | { type: "appendFrames"; frames: Omit<PublicGameReplayFrame, "index">[] }
  | { type: "goTo"; index: number }
  | { type: "togglePlay" }
  | { type: "tick" }
  | { type: "jumpToLive" };

function replayReducer(state: ReplayState, action: ReplayAction): ReplayState {
  switch (action.type) {
    case "loadFrames":
      return {
        frames: action.frames,
        currentIndex: action.frames.length - 1,
        isPlaying: false,
        reconstructedFromStandard: action.reconstructedFromStandard,
      };

    case "addFrame": {
      const lastFrame = state.frames[state.frames.length - 1];
      // Skip duplicate: same FEN as last frame means no new information
      if (lastFrame && lastFrame.fen === action.frame.fen) return state;

      const wasAtLast = state.currentIndex === state.frames.length - 1;
      const newFrame: PublicGameReplayFrame = { ...action.frame, index: state.frames.length };
      const newFrames = [...state.frames, newFrame];
      return {
        ...state,
        frames: newFrames,
        currentIndex: wasAtLast ? newFrames.length - 1 : state.currentIndex,
      };
    }

    case "appendFrames": {
      if (action.frames.length === 0) return state;
      const wasAtLast = state.currentIndex === state.frames.length - 1;
      let next = state.frames;
      for (const f of action.frames) {
        const last = next[next.length - 1];
        if (last && last.fen === f.fen) continue;
        next = [...next, { ...f, index: next.length }];
      }
      if (next === state.frames) return state;
      return {
        ...state,
        frames: next,
        currentIndex: wasAtLast ? next.length - 1 : state.currentIndex,
      };
    }

    case "goTo":
      return {
        ...state,
        currentIndex: Math.max(0, Math.min(action.index, state.frames.length - 1)),
        isPlaying: false,
      };

    case "togglePlay":
      if (state.frames.length === 0) return state;
      if (state.currentIndex >= state.frames.length - 1) return { ...state, isPlaying: false };
      return { ...state, isPlaying: !state.isPlaying };

    case "tick": {
      const next = state.currentIndex + 1;
      if (next >= state.frames.length) return { ...state, isPlaying: false };
      return { ...state, currentIndex: next };
    }

    case "jumpToLive":
      return { ...state, currentIndex: state.frames.length - 1, isPlaying: false };

    default:
      return state;
  }
}

const INITIAL_STATE: ReplayState = {
  frames: [],
  currentIndex: 0,
  isPlaying: false,
  reconstructedFromStandard: false,
};

interface SnapshotResult {
  frames: PublicGameReplayFrame[];
  reconstructedFromStandard: boolean;
}

function buildFramesFromSnapshot(snapshot: PublicGameSnapshot): SnapshotResult {
  const moves = snapshot.moves ?? [];

  if (moves.length === 0) {
    const fen = snapshot.fen ?? STANDARD_OPENING_FEN;
    return { frames: [{ index: 0, fen }], reconstructedFromStandard: false };
  }

  const frames: PublicGameReplayFrame[] = [{ index: 0, fen: STANDARD_OPENING_FEN }];
  try {
    const chess = new Chess(STANDARD_OPENING_FEN);
    for (const uci of moves) {
      const mv: ShortMove = {
        from: uci.slice(0, 2) as Square,
        to: uci.slice(2, 4) as Square,
        ...(uci.length > 4 ? { promotion: uci[4] as ShortMove["promotion"] } : {}),
      };
      chess.move(mv);
      frames.push({ index: frames.length, fen: chess.fen(), moveUci: uci });
    }

    // Verify reconstruction against the server-provided FEN when available.
    // If the final position doesn't match, the game used a non-standard starting
    // position and replaying from the standard start would show wrong boards.
    if (snapshot.fen) {
      const [snapPos, snapColor] = snapshot.fen.split(" ");
      const lastFrameFen = frames[frames.length - 1].fen;
      const [replayPos, replayColor] = lastFrameFen.split(" ");
      if (snapPos !== replayPos || snapColor !== replayColor) {
        // Mismatch — fall back to showing only the current server position.
        return { frames: [{ index: 0, fen: snapshot.fen }], reconstructedFromStandard: false };
      }
      // Verified: reconstruction matches the server position; no warning needed.
      return { frames, reconstructedFromStandard: false };
    }

    // No server FEN to compare against — reconstruction is unverified.
    return { frames, reconstructedFromStandard: true };
  } catch {
    const fen = snapshot.fen ?? STANDARD_OPENING_FEN;
    return { frames: [{ index: 0, fen }], reconstructedFromStandard: false };
  }
}

function buildFrameFromEvent(
  event: PublicTournamentEvent,
  prevFen: string
): Omit<PublicGameReplayFrame, "index"> | null {
  if (event.fen) {
    return { fen: event.fen, moveUci: event.lastMove, sourceEventType: event.type };
  }
  if (event.lastMove && event.lastMove.length >= 4) {
    const newFen = applyUciMove(prevFen, event.lastMove);
    if (newFen) {
      return { fen: newFen, moveUci: event.lastMove, sourceEventType: event.type };
    }
  }
  return null;
}

const GAME_EVENT_TYPES = new Set(["gameState", "move", "gameEnd", "gameStart"]);

export interface UsePublicGameReplayResult {
  frames: PublicGameReplayFrame[];
  currentFrame: PublicGameReplayFrame | null;
  currentIndex: number;
  totalFrames: number;
  isPlaying: boolean;
  isAtLive: boolean;
  canGoPrevious: boolean;
  canGoNext: boolean;
  reconstructedFromStandard: boolean;
  addSnapshot: (snapshot: PublicGameSnapshot) => void;
  addEvent: (event: PublicTournamentEvent) => void;
  refreshFromPoll: (snapshot: PublicGameSnapshot) => void;
  goToStart: () => void;
  goPrevious: () => void;
  togglePlay: () => void;
  goNext: () => void;
  goToEnd: () => void;
  jumpToLive: () => void;
  jumpTo: (index: number) => void;
}

export function usePublicGameReplay(): UsePublicGameReplayResult {
  const [state, dispatch] = useReducer(replayReducer, INITIAL_STATE);

  // Keep a ref to state so addEvent is stable (no [state.frames] dependency)
  const stateRef = useRef(state);
  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  // Playback timer — cleans up on unmount via useEffect return
  useEffect(() => {
    if (!state.isPlaying) return;
    const id = setInterval(() => dispatch({ type: "tick" }), 700);
    return () => clearInterval(id);
  }, [state.isPlaying]);

  const addSnapshot = useCallback((snapshot: PublicGameSnapshot) => {
    const result = buildFramesFromSnapshot(snapshot);
    dispatch({ type: "loadFrames", frames: result.frames, reconstructedFromStandard: result.reconstructedFromStandard });
  }, []);

  // Stable — reads latest state via ref, so no state in deps
  const addEvent = useCallback((event: PublicTournamentEvent) => {
    if (!GAME_EVENT_TYPES.has(event.type)) return;
    const current = stateRef.current;
    const prevFen =
      current.frames.length > 0
        ? current.frames[current.frames.length - 1].fen
        : STANDARD_OPENING_FEN;
    const partial = buildFrameFromEvent(event, prevFen);
    if (!partial) return;
    dispatch({ type: "addFrame", frame: partial });
  }, []);

  // Stable — appends only new moves from a polled snapshot without resetting position.
  // Primary dedup: move count (allMoves.length vs existing move frames count).
  // Secondary dedup: FEN equality in the appendFrames reducer for consecutive frames.
  // If the delta replay from the last FEN fails (bad accumulated FEN, illegal move),
  // falls back to a full rebuild from the authoritative moves list so the UI never
  // gets stuck with partial or inconsistent frames.
  const refreshFromPoll = useCallback((snapshot: PublicGameSnapshot) => {
    const current = stateRef.current;
    const existingMoveCount = current.frames.filter((f) => f.moveUci).length;
    const allMoves = snapshot.moves ?? [];
    if (allMoves.length <= existingMoveCount) return;

    const newMoves = allMoves.slice(existingMoveCount);
    const lastFen = current.frames[current.frames.length - 1]?.fen ?? STANDARD_OPENING_FEN;

    try {
      const chess = new Chess(lastFen);
      const partials: Omit<PublicGameReplayFrame, "index">[] = [];
      for (const uci of newMoves) {
        const mv: ShortMove = {
          from: uci.slice(0, 2) as Square,
          to: uci.slice(2, 4) as Square,
          ...(uci.length > 4 ? { promotion: uci[4] as ShortMove["promotion"] } : {}),
        };
        chess.move(mv);
        partials.push({ fen: chess.fen(), moveUci: uci, sourceEventType: "poll" });
      }
      if (partials.length > 0) {
        dispatch({ type: "appendFrames", frames: partials });
      }
    } catch {
      // Delta replay failed (bad last FEN or illegal move) — rebuild all frames from
      // the authoritative move list so the UI doesn't get stuck on partial frames.
      const result = buildFramesFromSnapshot(snapshot);
      dispatch({
        type: "loadFrames",
        frames: result.frames,
        reconstructedFromStandard: result.reconstructedFromStandard,
      });
    }
  }, []);

  const goToStart = useCallback(() => dispatch({ type: "goTo", index: 0 }), []);
  const goPrevious = useCallback(
    () => dispatch({ type: "goTo", index: stateRef.current.currentIndex - 1 }),
    []
  );
  const togglePlay = useCallback(() => dispatch({ type: "togglePlay" }), []);
  const goNext = useCallback(
    () => dispatch({ type: "goTo", index: stateRef.current.currentIndex + 1 }),
    []
  );
  const goToEnd = useCallback(
    () => dispatch({ type: "goTo", index: stateRef.current.frames.length - 1 }),
    []
  );
  const jumpToLive = useCallback(() => dispatch({ type: "jumpToLive" }), []);
  const jumpTo = useCallback((index: number) => dispatch({ type: "goTo", index }), []);

  const currentFrame = state.frames[state.currentIndex] ?? null;
  const isAtLive = state.frames.length === 0 || state.currentIndex === state.frames.length - 1;

  return {
    frames: state.frames,
    currentFrame,
    currentIndex: state.currentIndex,
    totalFrames: state.frames.length,
    isPlaying: state.isPlaying,
    isAtLive,
    canGoPrevious: state.currentIndex > 0,
    canGoNext: state.currentIndex < state.frames.length - 1,
    reconstructedFromStandard: state.reconstructedFromStandard,
    addSnapshot,
    addEvent,
    refreshFromPoll,
    goToStart,
    goPrevious,
    togglePlay,
    goNext,
    goToEnd,
    jumpToLive,
    jumpTo,
  };
}
