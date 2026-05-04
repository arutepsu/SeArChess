import { runPlayerLifecycle } from './lib/gameplay.js';

const LOAD_GAMEPLAY = {
  plies: 4,
  thinkTimeSeconds: 0.1,
};

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: 50,
      duration: '1m',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  runPlayerLifecycle(LOAD_GAMEPLAY);
}
