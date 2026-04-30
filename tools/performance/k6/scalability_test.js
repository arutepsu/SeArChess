import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  // Rely on CLI args: k6 run --vus 10 --duration 10s
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const url = __ENV.TARGET_URL || 'http://127.0.0.1:5173/game';
  const res = http.get(url);
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(0.1);
}
