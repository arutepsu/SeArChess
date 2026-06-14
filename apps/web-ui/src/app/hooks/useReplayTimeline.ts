import { useEffect, useRef, useState } from "react";
import type { GameState } from "../../api/types";
import type { MoveHistoryEntryDto } from "../../api/backendTypes";
import { getReplayFrame } from "../../api/client";
import { mapGameSnapshotToGameState } from "../../api/mapper";

export function useReplayTimeline(game: GameState | undefined) {
  const [timelinePly, setTimelinePly] = useState(0);
  const [timelineTotalPlies, setTimelineTotalPlies] = useState(0);
  const [timelineRawMoves, setTimelineRawMoves] = useState<MoveHistoryEntryDto[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineError, setTimelineError] = useState<string | null>(null);
  const [replayGame, setReplayGame] = useState<GameState | null>(null);
  const previousTimelineTotalRef = useRef(0);

  // Keeps timeline at the live edge when the user has not moved back in history;
  // otherwise clamps ply to the new total without jumping back to live.
  useEffect(() => {
    if (!game) {
      setTimelinePly(0);
      setTimelineTotalPlies(0);
      setTimelineRawMoves([]);
      setTimelineError(null);
      setReplayGame(null);
      previousTimelineTotalRef.current = 0;
      return;
    }

    const nextTotalPlies = game.moves.length;
    const wasAtLiveEdge = timelinePly >= previousTimelineTotalRef.current;

    setTimelineTotalPlies(nextTotalPlies);
    if (wasAtLiveEdge) {
      setTimelinePly(nextTotalPlies);
    } else {
      setTimelinePly((value) => Math.min(value, nextTotalPlies));
    }

    previousTimelineTotalRef.current = nextTotalPlies;
  }, [game, timelinePly]);

  // Loads the replay frame from the server whenever game or ply position changes.
  useEffect(() => {
    if (!game?.id) return;

    const currentTotalPlies = game.moves.length;
    if (timelinePly > currentTotalPlies) {
      setTimelinePly(currentTotalPlies);
      setTimelineError(null);
      setReplayGame(null);
      return;
    }

    let active = true;
    setTimelineLoading(true);
    setTimelineError(null);

    getReplayFrame(game.id, timelinePly)
      .then((frame) => {
        if (!active) return;
        setTimelineTotalPlies(frame.totalPlies);
        setTimelineRawMoves(frame.rawMoves);

        if (timelinePly < frame.totalPlies) {
          setReplayGame(mapGameSnapshotToGameState(frame.game));
        } else {
          setReplayGame(null);
        }
      })
      .catch((error) => {
        if (!active) return;
        setTimelineError(
          error instanceof Error
            ? error.message
            : "Replay timeline could not be loaded."
        );
      })
      .finally(() => {
        if (active) setTimelineLoading(false);
      });

    return () => {
      active = false;
    };
  }, [game?.id, game?.moves.length, timelinePly]);

  return {
    timelinePly,
    setTimelinePly,
    timelineTotalPlies,
    timelineRawMoves,
    timelineLoading,
    timelineError,
    replayGame,
  };
}
