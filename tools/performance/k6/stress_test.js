import { runPlayerLifecycle } from './lib/gameplay.js';

const STRESS_GAMEPLAY = {
  plies: 4,
  thinkTimeSeconds: 0.1,
};

export const options = {
  summaryTrendStats: ['med', 'p(95)', 'p(99)'],
  stages: [
    { duration: '30s', target: 100 },
    { duration: '30s', target: 200 },
    { duration: '30s', target: 400 },
    { duration: '30s', target: 600 },
    { duration: '30s', target: 800 },
    { duration: '30s', target: 1000 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'],
    http_req_failed: ['rate<0.05'],
  },
};

export default function () {
  runPlayerLifecycle(STRESS_GAMEPLAY);
}
