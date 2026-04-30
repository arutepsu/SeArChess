import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 50 }, // ramp up to 50 VUs
    { duration: '30s', target: 50 }, // stay at 50 VUs for 30 seconds
    { duration: '10s', target: 0 }, // ramp down to 0 VUs
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],   // Error rate must be less than 1%
  },
};

export default function () {
  const url = __ENV.TARGET_URL || 'http://127.0.0.1:5173/game';

  const res = http.get(url);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(0.1);
}
