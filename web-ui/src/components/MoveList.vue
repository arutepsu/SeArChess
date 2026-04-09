<script setup lang="ts">
import type { MoveRecord } from "../api/types";

defineProps<{
  moves: MoveRecord[];
}>();
</script>

<template>
  <section class="panel" aria-label="Move list">
    <header>
      <h2>Move Log</h2>
      <p>Every step, every pivot.</p>
    </header>
    <div class="move-list">
      <div v-if="moves.length === 0" class="empty">No moves yet.</div>
      <div v-for="move in moves" :key="move.ply" class="move">
        <span class="ply">#{{ move.ply }}</span>
        <span class="notation">{{ move.notation }}</span>
        <span class="coords">{{ move.from }} -> {{ move.to }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel {
  background: rgba(20, 21, 27, 0.88);
  border-radius: 24px;
  padding: 20px;
  color: #f4efe6;
  min-height: 240px;
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

.move-list {
  display: grid;
  gap: 12px;
}

.move {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 16px;
  padding: 10px 12px;
}

.ply {
  font-weight: 700;
  color: #ffce74;
}

.notation {
  font-weight: 600;
  letter-spacing: 0.05rem;
}

.coords {
  font-size: 0.8rem;
  opacity: 0.7;
}

.empty {
  opacity: 0.6;
  font-style: italic;
}
</style>
