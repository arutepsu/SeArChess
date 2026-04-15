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

.message {
  margin: 0;
  font-size: 0.95rem;
  opacity: 0.8;
}
</style>
