<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { BoardMatrix, GameState, MoveRequest, PieceCode } from "./api/types";
import {
  apiBaseUrl,
  exportPgn,
  getGameState,
  getLegalMoves,
  getStatus,
  redoMove,
  startNewGame,
  submitMove,
  undoMove
} from "./api/client";
import ChessBoard from "./components/ChessBoard.vue";
import ControlPanel from "./components/ControlPanel.vue";
import MoveList from "./components/MoveList.vue";
import StatusBanner from "./components/StatusBanner.vue";

const game = ref<GameState>();
const connection = ref<"connected" | "offline" | "loading">("loading");
const message = ref<string>();
const selectedSquare = ref<string>();
const legalMoves = ref<string[]>([]);
const busy = ref(false);
const pgnExport = ref<string>("");
const previousBoard = ref<BoardMatrix | null>(null);
const animationPlan = ref<BoardAnimation | null>(null);
const animationCounter = ref(0);
const baseClockMs = 10 * 60 * 1000;
const whiteClockMs = ref(baseClockMs);
const blackClockMs = ref(baseClockMs);
const lastTickMs = ref<number | null>(null);
const clockInterval = ref<number | null>(null);

type BoardAnimation = {
  id: number;
  from: string;
  to: string;
  movingPiece: PieceCode;
  capturedPiece?: PieceCode;
  isCapture: boolean;
};

const loadGame = async () => {
  connection.value = "loading";
  try {
    await getStatus();
    const state = await getGameState();
    game.value = state;
    previousBoard.value = state.board;
    animationPlan.value = null;
    legalMoves.value = [];
    connection.value = "connected";
    message.value = undefined;
  } catch (error) {
    connection.value = "offline";
    message.value =
      error instanceof Error
        ? `Service offline. ${error.message}`
        : "Service offline.";
  }
};

const resetClocks = () => {
  whiteClockMs.value = baseClockMs;
  blackClockMs.value = baseClockMs;
  lastTickMs.value = performance.now();
};

const clockRunning = computed(() => {
  const status = game.value?.status;
  return status === "active" || status === "check";
});

const tickClock = () => {
  const now = performance.now();
  const last = lastTickMs.value ?? now;
  lastTickMs.value = now;
  if (!clockRunning.value || !game.value) return;
  const delta = Math.max(0, now - last);
  if (game.value.activeColor === "white") {
    whiteClockMs.value = Math.max(0, whiteClockMs.value - delta);
  } else {
    blackClockMs.value = Math.max(0, blackClockMs.value - delta);
  }
};

const handleSelect = async (square: string) => {
  if (!game.value || busy.value) return;
  if (!selectedSquare.value) {
    selectedSquare.value = square;
    try {
      const result = await getLegalMoves(square);
      legalMoves.value = result.moves;
    } catch {
      legalMoves.value = [];
    }
    return;
  }

  if (selectedSquare.value === square) {
    selectedSquare.value = undefined;
    legalMoves.value = [];
    return;
  }

  if (legalMoves.value.length > 0 && !legalMoves.value.includes(square)) {
    message.value = "Select a legal target square.";
    return;
  }

  const move: MoveRequest = { from: selectedSquare.value, to: square };
  const prevBoard = game.value.board;
  selectedSquare.value = undefined;
  legalMoves.value = [];
  busy.value = true;
  try {
    game.value = await submitMove(move);
    previousBoard.value = prevBoard;
    animationPlan.value = planAnimation(prevBoard, game.value);
    message.value = undefined;
  } catch (error) {
    message.value =
      error instanceof Error ? error.message : "Move rejected by service.";
  } finally {
    busy.value = false;
  }
};

const handleNewGame = async () => {
  busy.value = true;
  try {
    game.value = await startNewGame({});
    message.value = undefined;
    selectedSquare.value = undefined;
    legalMoves.value = [];
    previousBoard.value = game.value.board;
    animationPlan.value = null;
  } catch (error) {
    message.value = error instanceof Error ? error.message : "Failed to start game.";
  } finally {
    busy.value = false;
  }
};

const handleUndo = async () => {
  busy.value = true;
  try {
    const prevBoard = game.value?.board ?? null;
    game.value = await undoMove();
    selectedSquare.value = undefined;
    legalMoves.value = [];
    if (prevBoard && game.value) {
      previousBoard.value = prevBoard;
      animationPlan.value = planAnimation(prevBoard, game.value);
    }
  } catch (error) {
    message.value = error instanceof Error ? error.message : "Undo failed.";
  } finally {
    busy.value = false;
  }
};

const handleRedo = async () => {
  busy.value = true;
  try {
    const prevBoard = game.value?.board ?? null;
    game.value = await redoMove();
    selectedSquare.value = undefined;
    legalMoves.value = [];
    if (prevBoard && game.value) {
      previousBoard.value = prevBoard;
      animationPlan.value = planAnimation(prevBoard, game.value);
    }
  } catch (error) {
    message.value = error instanceof Error ? error.message : "Redo failed.";
  } finally {
    busy.value = false;
  }
};

const handleExport = async () => {
  busy.value = true;
  try {
    const result = await exportPgn();
    pgnExport.value = result.pgn;
  } catch (error) {
    message.value = error instanceof Error ? error.message : "Export failed.";
  } finally {
    busy.value = false;
  }
};

onMounted(() => {
  loadGame();
  lastTickMs.value = performance.now();
  clockInterval.value = window.setInterval(tickClock, 250);
});

onBeforeUnmount(() => {
  if (clockInterval.value !== null) {
    window.clearInterval(clockInterval.value);
  }
});

watch(
  () => game.value?.id,
  (next, prev) => {
    if (next && next !== prev) {
      resetClocks();
    }
  }
);

const handleAnimationFinished = (id: number) => {
  if (animationPlan.value?.id === id) {
    animationPlan.value = null;
  }
};

const planAnimation = (prevBoard: BoardMatrix, nextGame: GameState | undefined): BoardAnimation | null => {
  if (!nextGame?.lastMove) return null;
  const from = nextGame.lastMove.from;
  const to = nextGame.lastMove.to;
  const movingPiece = pieceAt(prevBoard, from);
  if (!movingPiece) return null;
  const capturedPiece = pieceAt(prevBoard, to) ?? undefined;
  animationCounter.value += 1;
  return {
    id: animationCounter.value,
    from,
    to,
    movingPiece,
    capturedPiece,
    isCapture: Boolean(capturedPiece)
  };
};

const pieceAt = (board: BoardMatrix, square: string): PieceCode | null => {
  const position = squareToIndex(square);
  if (!position) return null;
  return board[position.row]?.[position.col] ?? null;
};

const squareToIndex = (square: string): { row: number; col: number } | null => {
  if (square.length !== 2) return null;
  const files = "abcdefgh";
  const file = square[0].toLowerCase();
  const rank = Number(square[1]);
  const col = files.indexOf(file);
  if (col < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  return { row: 8 - rank, col };
};
</script>

<template>
  <div class="app">
    <div class="leaf-layer" aria-hidden="true">
      <span class="leaf leaf-1"></span>
      <span class="leaf leaf-2"></span>
      <span class="leaf leaf-3"></span>
      <span class="leaf leaf-4"></span>
      <span class="leaf leaf-5"></span>
      <span class="leaf leaf-6"></span>
    </div>
    <!-- <StatusBanner :game="game" :connection="connection" :message="message" /> -->
    <main class="layout">
      <ChessBoard
        v-if="game"
        :board="game.board"
        :selected-square="selectedSquare"
        :legal-moves="legalMoves"
        :animation="animationPlan"
        @select="handleSelect"
        @animation-finished="handleAnimationFinished"
      />
      <section v-else class="board-shell placeholder">
        <div class="loading">Waiting for game data...</div>
      </section>
      <aside class="side">
        <ControlPanel
          :game="game"
          :busy="busy"
          :white-time-ms="whiteClockMs"
          :black-time-ms="blackClockMs"
          :active-color="game?.activeColor"
          :clock-running="clockRunning"
          @new-game="handleNewGame"
          @undo="handleUndo"
          @redo="handleRedo"
          @export="handleExport"
        />
        <MoveList :moves="game?.moves ?? []" />
        <section class="panel capture-panel">
          <header>
            <h2>Captured</h2>
            <p>Pieces claimed during the match.</p>
          </header>
          <div class="captured">
            <span v-if="!game || game.captured.length === 0">None yet.</span>
            <span v-for="(piece, index) in game?.captured" :key="`${piece}-${index}`">
              {{ piece }}
            </span>
          </div>
        </section>
      </aside>
    </main>
    <section class="panel export" v-if="pgnExport">
      <div class="export-header">
        <h2>PGN Export</h2>
        <button type="button" @click="pgnExport = ''">Close</button>
      </div>
      <pre>{{ pgnExport }}</pre>
      <span class="hint">API base: {{ apiBaseUrl }}</span>
    </section>
  </div>
</template>

<style scoped>
.app {
  display: grid;
  gap: 24px;
  position: relative;
}

.app > :not(.leaf-layer) {
  position: relative;
  z-index: 1;
}

.layout {
  display: grid;
  grid-template-columns: minmax(360px, 1.6fr) minmax(200px, 0.4fr);
  gap: 36px;
  align-items: start;
}

.side {
  display: grid;
  gap: 16px;
  width: 100%;
  max-width: 280px;
  justify-self: end;
  margin-left: 32px;
}

.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #f4efe6;
  min-height: 420px;
}

.loading {
  font-size: 1rem;
  letter-spacing: 0.12rem;
  text-transform: uppercase;
  opacity: 0.7;
}

.panel {
  background: rgba(20, 21, 27, 0.88);
  border-radius: 24px;
  padding: 24px;
  color: #f4efe6;
}

.capture-panel .captured {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-weight: 600;
  letter-spacing: 0.05rem;
}

.export {
  margin-top: 8px;
}

.export-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.export-header button {
  border: none;
  background: rgba(255, 255, 255, 0.1);
  color: #f4efe6;
  border-radius: 999px;
  padding: 6px 14px;
  cursor: pointer;
}

pre {
  background: rgba(0, 0, 0, 0.4);
  padding: 16px;
  border-radius: 16px;
  white-space: pre-wrap;
  word-break: break-word;
}

.hint {
  display: block;
  margin-top: 10px;
  font-size: 0.8rem;
  opacity: 0.6;
}

@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
