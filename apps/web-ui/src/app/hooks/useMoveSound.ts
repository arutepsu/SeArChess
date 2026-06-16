import { useEffect, useRef } from "react";
import type { GameState } from "../../api/types";
import { playMoveSound } from "../../utils/sound";

export function useMoveSound(game: GameState | undefined): void {
  const lastPlayedGameId = useRef<string | null>(null);
  const prevMovesLength = useRef<number | null>(null);

  useEffect(() => {
    if (!game) {
      lastPlayedGameId.current = null;
      prevMovesLength.current = null;
      return;
    }

    const gameId = game.id;
    const currentLength = game.moves.length;

    if (
      lastPlayedGameId.current === gameId &&
      prevMovesLength.current !== null &&
      currentLength > prevMovesLength.current
    ) {
      playMoveSound();
    }

    lastPlayedGameId.current = gameId;
    prevMovesLength.current = currentLength;
  }, [game]);
}
