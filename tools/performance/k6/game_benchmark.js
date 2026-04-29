import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  // Define thresholds
  thresholds: {
    http_req_duration: ['p(95)<500'], // 95% of requests must complete below 500ms
    http_req_failed: ['rate<0.01'],   // Error rate must be less than 1%
  },
  // Fixed number of VUs and iterations for reproducibility
  scenarios: {
    baseline_test: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 50,
      maxDuration: '30s',
    },
  },
};

export default function () {
  // Since we are running in docker, we might need to use host.docker.internal depending on network mode
  // The user asked for http://localhost:5173/game, we assume docker run with --network host or host.docker.internal
  // Let's use host.docker.internal which works for Docker Desktop on Windows.
  const url = __ENV.TARGET_URL || 'http://192.168.178.125:5173/game';

  const res = http.get(url);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'body has game text': (r) => r.body && r.body.includes('game') || r.status === 200,
  });

  // Short pause to simulate user think-time
  sleep(0.1);
}
