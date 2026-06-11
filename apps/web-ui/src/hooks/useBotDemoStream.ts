import { useState, useEffect, useRef, useMemo } from "react";
import { Chess } from "chess.js";
import type { GameState, BoardMatrix, PieceCode } from "../api/types";
import { computeCapturedPieces } from "../api/mapper";

export interface BotWebSocketData {
  moves?: string;
  wtime?: number | null;
  btime?: number | null;
  gameId?: string | null;
  botColor?: "white" | "black";
}

export type BotConnectionState = "idle" | "connecting" | "live" | "disconnected";

export interface UseBotDemoStreamResult {
  botGameData: BotWebSocketData | null;
  mappedBotGame: GameState | null;
  botWhiteClockMs: number | null;
  botBlackClockMs: number | null;
  hasNewBotMoveNotification: boolean;
  botConnectionState: BotConnectionState;
}

function mapBotDataToGameState(data: BotWebSocketData): GameState {
  const chess = new Chess();
  const moveList = data.moves ? data.moves.split(" ").filter(Boolean) : [];
  for (const moveStr of moveList) {
    if (moveStr.length >= 4) {
      const from = moveStr.substring(0, 2);
      const to = moveStr.substring(2, 4);
      const promotion = moveStr.length > 4 ? moveStr.substring(4, 5).toLowerCase() : undefined;
      chess.move({ from, to, promotion });
    }
  }

  const chessBoard = chess.board();
  const board: BoardMatrix = Array.from({ length: 8 }, () => Array(8).fill(null));
  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      const piece = chessBoard[r][c];
      if (piece) {
        board[r][c] = `${piece.color}${piece.type.toUpperCase()}` as PieceCode;
      }
    }
  }

  const captured = computeCapturedPieces(board);

  const verboseMoves = chess.history({ verbose: true });
  const moves = verboseMoves.map((m, index) => {
    const record: any = {
      ply: index + 1,
      notation: m.san,
      from: m.from,
      to: m.to,
    };
    if (m.captured) {
      const oppColor = m.color === "w" ? "b" : "w";
      record.captured = `${oppColor}${m.captured.toUpperCase()}` as PieceCode;
    }
    if (m.promotion) {
      record.promotion = `${m.color}${m.promotion.toUpperCase()}` as PieceCode;
    }
    return record;
  });

  let status: any = "active";
  if (chess.in_checkmate()) {
    status = "checkmate";
  } else if (chess.in_draw()) {
    status = "draw";
  } else if (chess.in_check()) {
    status = "check";
  }

  const activeColor = chess.turn() === "w" ? "white" : "black";
  const winner = status === "checkmate" ? (activeColor === "white" ? "black" : "white") : undefined;

  return {
    id: data.gameId ?? "",
    board,
    activeColor,
    status,
    winner,
    moves,
    captured,
    fullMove: Math.floor((moves.length + 2) / 2),
    halfMoveClock: 0,
    lastMove: moves.length > 0 ? moves[moves.length - 1] : undefined,
    legalTargetsByFrom: {},
  };
}

export function useBotDemoStream(activeTab: "local" | "bot"): UseBotDemoStreamResult {
  const [botGameData, setBotGameData] = useState<BotWebSocketData | null>(null);
  const [botWhiteClockMs, setBotWhiteClockMs] = useState<number | null>(null);
  const [botBlackClockMs, setBotBlackClockMs] = useState<number | null>(null);
  const [hasNewBotMoveNotification, setHasNewBotMoveNotification] = useState(false);
  const [botConnectionState, setBotConnectionState] = useState<BotConnectionState>("idle");

  const activeTabRef = useRef(activeTab);
  const prevBotMovesCountRef = useRef<number>(0);
  const prevBotGameIdRef = useRef<string | null>(null);

  useEffect(() => {
    activeTabRef.current = activeTab;
    if (activeTab === "bot") {
      setHasNewBotMoveNotification(false);
    }
  }, [activeTab]);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
    let isComponentMounted = true;

    function connect() {
      if (!isComponentMounted) return;
      setBotConnectionState("connecting");

      const botWsUrl = import.meta.env.VITE_BOT_WS_URL as string | undefined;
      if (!botWsUrl) {
        setBotConnectionState("disconnected");
        return;
      }
      ws = new WebSocket(botWsUrl);

      ws.onopen = () => {
        if (!isComponentMounted) return;
        setBotConnectionState("live");
      };

      ws.onmessage = (event) => {
        if (!isComponentMounted) return;
        try {
          const data: BotWebSocketData = JSON.parse(event.data);
          setBotGameData(data);

          if (data.wtime !== undefined && data.wtime !== null) {
            setBotWhiteClockMs(data.wtime);
          }
          if (data.btime !== undefined && data.btime !== null) {
            setBotBlackClockMs(data.btime);
          }

          const movesStr = data.moves || "";
          const movesCount = movesStr.split(" ").filter(Boolean).length;

          if (activeTabRef.current === "local") {
            if (
              prevBotGameIdRef.current !== null &&
              (data.gameId !== prevBotGameIdRef.current ||
                movesCount > prevBotMovesCountRef.current)
            ) {
              setHasNewBotMoveNotification(true);
            }
          }

          prevBotMovesCountRef.current = movesCount;
          prevBotGameIdRef.current = data.gameId ?? null;
        } catch (e) {
          console.error("Failed to parse bot websocket message: ", e);
        }
      };

      ws.onclose = () => {
        if (!isComponentMounted) return;
        setBotConnectionState("disconnected");
        reconnectTimeoutId = setTimeout(() => {
          connect();
        }, 3000);
      };

      ws.onerror = () => {
        if (!isComponentMounted) return;
        ws?.close();
      };
    }

    connect();

    return () => {
      isComponentMounted = false;
      if (ws) ws.close();
      if (reconnectTimeoutId) clearTimeout(reconnectTimeoutId);
    };
  }, []);

  // Bot clock tick
  useEffect(() => {
    let lastTick = performance.now();
    const intervalId = window.setInterval(() => {
      const now = performance.now();
      const delta = Math.max(0, now - lastTick);
      lastTick = now;

      if (botGameData) {
        const chess = new Chess();
        const moveList = botGameData.moves
          ? botGameData.moves.split(" ").filter(Boolean)
          : [];
        for (const moveStr of moveList) {
          if (moveStr.length >= 4) {
            const from = moveStr.substring(0, 2);
            const to = moveStr.substring(2, 4);
            const promotion =
              moveStr.length > 4 ? moveStr.substring(4, 5).toLowerCase() : undefined;
            chess.move({ from, to, promotion });
          }
        }

        if (!chess.game_over()) {
          const turn = chess.turn();
          if (turn === "w") {
            setBotWhiteClockMs((t) => (t !== null ? Math.max(0, t - delta) : null));
          } else {
            setBotBlackClockMs((t) => (t !== null ? Math.max(0, t - delta) : null));
          }
        }
      }
    }, 250);

    return () => window.clearInterval(intervalId);
  }, [botGameData]);

  const mappedBotGame = useMemo(() => {
    if (!botGameData) return null;
    return mapBotDataToGameState(botGameData);
  }, [botGameData]);

  return {
    botGameData,
    mappedBotGame,
    botWhiteClockMs,
    botBlackClockMs,
    hasNewBotMoveNotification,
    botConnectionState,
  };
}
