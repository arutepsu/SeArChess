<script setup lang="ts">
import type { GameState } from "../api/types";

defineProps<{
  game?: GameState;
  connection: "connected" | "offline" | "loading";
  message?: string;
}>();
</script>

<template>
  <section class="banner" aria-live="polite">
    <div>
      <span class="title">SeArChess Web Console</span>
      <p>Strategic command, real-time sync.</p>
    </div>
    <div class="meta">
      <span class="pill" :class="connection">{{ connection }}</span>
      <span class="pill">Game: {{ game?.id ?? "not loaded" }}</span>
      <span class="pill" v-if="game?.lastMove">
        Last: {{ game?.lastMove?.notation }}
      </span>
    </div>
    <p v-if="message" class="message">{{ message }}</p>
  </section>
</template>

<style scoped>
.banner {
  background: linear-gradient(130deg, #1f2430, #3d2b1f);
  border-radius: 26px;
  padding: 20px 24px;
  color: #f8f1e6;
  box-shadow: 0 25px 40px rgba(0, 0, 0, 0.25);
}

.title {
  text-transform: uppercase;
  font-size: 1.1rem;
  letter-spacing: 0.2rem;
}

p {
  margin: 6px 0 0;
  opacity: 0.7;
}

.meta {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.pill {
  background: rgba(255, 255, 255, 0.1);
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 0.75rem;
  letter-spacing: 0.1rem;
  text-transform: uppercase;
}

.connected {
  background: rgba(62, 181, 117, 0.2);
  color: #b4f5c5;
}

.offline {
  background: rgba(211, 92, 72, 0.2);
  color: #ffb7aa;
}

.loading {
  background: rgba(255, 206, 116, 0.2);
  color: #ffe6ad;
}

.message {
  margin-top: 12px;
  font-size: 0.85rem;
  opacity: 0.75;
}
</style>
