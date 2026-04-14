<script setup lang="ts">
import type { GameState, PlayerColor } from "../api/types";

const props = defineProps<{
  game?: GameState;
  busy: boolean;
  whiteTimeMs?: number;
  blackTimeMs?: number;
  activeColor?: PlayerColor;
  clockRunning?: boolean;
}>();

const emit = defineEmits<{
  (event: "new-game"): void;
  (event: "undo"): void;
  (event: "redo"): void;
  (event: "export"): void;
}>();

const formatTime = (ms?: number) => {
  const totalSeconds = Math.max(0, Math.floor((ms ?? 0) / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
};
</script>

<template>
  <section class="panel" aria-label="Controls">
    <header>
      <h2>Command Deck</h2>
      <p>Direct the battle from here.</p>
    </header>
    <div class="clocks">
      <div
        class="clock"
        :class="[props.activeColor === 'white' && props.clockRunning ? 'is-active' : '']"
      >
        <span class="label">White</span>
        <strong class="clock-time">{{ formatTime(props.whiteTimeMs) }}</strong>
      </div>
      <div
        class="clock"
        :class="[props.activeColor === 'black' && props.clockRunning ? 'is-active' : '']"
      >
        <span class="label">Black</span>
        <strong class="clock-time">{{ formatTime(props.blackTimeMs) }}</strong>
      </div>
    </div>
    <div class="status">
      <div>
        <span class="label">Status</span>
        <strong>{{ game?.status ?? "idle" }}</strong>
      </div>
      <div>
        <span class="label">Full move</span>
        <strong>{{ game?.fullMove ?? 0 }}</strong>
      </div>
      <div>
        <span class="label">Half move</span>
        <strong>{{ game?.halfMoveClock ?? 0 }}</strong>
      </div>
    </div>
    <div class="actions">
      <button type="button" :disabled="busy" @click="emit('new-game')">
        New Game
      </button>
      <button type="button" :disabled="busy" @click="emit('undo')">
        Undo
      </button>
      <button type="button" :disabled="busy" @click="emit('redo')">
        Redo
      </button>
      <button type="button" :disabled="busy" @click="emit('export')">
        Export PGN
      </button>
    </div>
  </section>
</template>

<style scoped>
.panel {
  background: rgba(20, 21, 27, 0.88);
  border-radius: 24px;
  padding: 20px;
  color: #f4efe6;
}

header h2 {
  margin: 0;
  font-size: 1.1rem;
  letter-spacing: 0.1rem;
  text-transform: uppercase;
}

header p {
  margin: 6px 0 16px;
  font-size: 0.8rem;
  opacity: 0.6;
}

.status {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.clocks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.clock {
  display: grid;
  gap: 6px;
  align-items: center;
  padding: 10px 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
}

.clock.is-active {
  box-shadow: 0 0 0 1px rgba(255, 206, 116, 0.55), 0 12px 22px rgba(0, 0, 0, 0.35);
}

.clock-time {
  font-size: 1.2rem;
  letter-spacing: 0.08rem;
}

.label {
  display: block;
  font-size: 0.65rem;
  letter-spacing: 0.12rem;
  text-transform: uppercase;
  opacity: 0.6;
}

strong {
  display: block;
  margin-top: 6px;
  font-size: 1rem;
}

.actions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
}

button {
  border: none;
  border-radius: 14px;
  padding: 10px 12px;
  background: #ffce74;
  color: #231710;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

button:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 18px rgba(0, 0, 0, 0.35);
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  box-shadow: none;
}
</style>
