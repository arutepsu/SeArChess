import { runPlayerLifecycle } from './lib/gameplay.js';

const SPIKE_GAMEPLAY = {
  plies: 4,
  thinkTimeSeconds: 0.1,
};

export const options = {
  stages: [
    { duration: '10s', target: 20 },
    { duration: '10s', target: 150 },
    { duration: '20s', target: 150 },
    { duration: '10s', target: 20 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'],
    http_req_failed: ['rate<0.05'],
  },
};

export default function () {
  runPlayerLifecycle(SPIKE_GAMEPLAY);
}
