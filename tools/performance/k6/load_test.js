import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json', Accept: 'application/json' } };

let sessionId;
let gameId;

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

function isJson(body) {
  try {
    JSON.parse(body);
    return true;
  } catch (_) {
    return false;
  }
}

function bodyIsNotEmpty(response) {
  return Boolean(response.body && response.body.length > 0);
}

function ensureSession() {
  if (sessionId && gameId) {
    return true;
  }

  const response = http.post(
    `${BASE_URL}/sessions`,
    JSON.stringify({ mode: 'HumanVsHuman' }),
    JSON_HEADERS
  );

  const ok = check(response, {
    'create session status is 201': (r) => r.status === 201,
    'create session body is not empty': bodyIsNotEmpty,
    'create session body is valid JSON': (r) => bodyIsNotEmpty(r) && isJson(r.body),
    'create session returns ids': (r) => {
      if (!bodyIsNotEmpty(r) || !isJson(r.body)) return false;
      const body = JSON.parse(r.body);
      return Boolean(body.session && body.session.sessionId && body.game && body.game.gameId);
    },
  });

  if (!ok) {
    return false;
  }

  const body = JSON.parse(response.body);
  sessionId = body.session.sessionId;
  gameId = body.game.gameId;
  return true;
}

export default function () {
  const health = http.get(`${BASE_URL}/health`, JSON_HEADERS);
  check(health, {
    'health status is 200': (r) => r.status === 200,
    'health body is valid JSON': (r) => bodyIsNotEmpty(r) && isJson(r.body),
  });

  if (!ensureSession()) {
    sleep(0.1);
    return;
  }

  const responses = http.batch([
    ['GET', `${BASE_URL}/sessions/${sessionId}`, null, JSON_HEADERS],
    ['GET', `${BASE_URL}/sessions/${sessionId}/state`, null, JSON_HEADERS],
    ['GET', `${BASE_URL}/games/${gameId}`, null, JSON_HEADERS],
    ['GET', `${BASE_URL}/games/${gameId}/legal-moves`, null, JSON_HEADERS],
  ]);

  check(responses[0], {
    'session read status is 200': (r) => r.status === 200,
    'session read body is valid JSON': (r) => bodyIsNotEmpty(r) && isJson(r.body),
  });
  check(responses[1], {
    'session state status is 200': (r) => r.status === 200,
    'session state body is valid JSON': (r) => bodyIsNotEmpty(r) && isJson(r.body),
  });
  check(responses[2], {
    'game read status is 200': (r) => r.status === 200,
    'game read body is valid JSON': (r) => bodyIsNotEmpty(r) && isJson(r.body),
  });
  check(responses[3], {
    'legal moves status is 200': (r) => r.status === 200,
    'legal moves body is valid JSON': (r) => bodyIsNotEmpty(r) && isJson(r.body),
  });

  sleep(0.1);
}
