import { runPlayerLifecycle } from './lib/gameplay.js';

const BASELINE_GAMEPLAY = {
  plies: 4,
  thinkTimeSeconds: 0.2,
};

export const options = {
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
  scenarios: {
    baseline: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 10,
      maxDuration: '2m',
    },
  },
};

export default function () {
  runPlayerLifecycle(BASELINE_GAMEPLAY);
}
