import http from 'k6/http';
import { check, group, sleep } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:10000/api').replace(/\/+$/, '');

const JSON_PARAMS = {
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
};

function bodyIsNotEmpty(response) {
  return Boolean(response.body && response.body.length > 0);
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (_) {
    return null;
  }
}

function hasValidJson(response) {
  return bodyIsNotEmpty(response) && parseJson(response.body) !== null;
}

function checkedJson(response, name, expectedStatus) {
  const ok = check(response, {
    [`${name} status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
    [`${name} body is not empty`]: bodyIsNotEmpty,
    [`${name} body is valid JSON`]: hasValidJson,
  });

  if (!ok) {
    return null;
  }

  return parseJson(response.body);
}

export function createSession() {
  const response = http.post(
    `${BASE_URL}/sessions`,
    JSON.stringify({ mode: 'HumanVsHuman' }),
    JSON_PARAMS
  );
    console.log(`STATUS: ${response.status}`);
  console.log(`BODY: ${response.body}`);
  const body = checkedJson(response, 'create session', 201);

  const hasIds = check(body, {
    'create session returns sessionId and gameId': (json) =>
      Boolean(json && json.session && json.session.sessionId && json.game && json.game.gameId),
  });

  if (!hasIds) {
    return null;
  }

  return {
    sessionId: body.session.sessionId,
    gameId: body.game.gameId,
  };
}

export function fetchLegalMoves(gameId) {
  const response = http.get(`${BASE_URL}/games/${gameId}/legal-moves`, JSON_PARAMS);
  const body = checkedJson(response, 'fetch legal moves', 200);

  const hasMoves = check(body, {
    'legal moves response has moves array': (json) => Boolean(json && Array.isArray(json.moves)),
  });

  if (!hasMoves) {
    return null;
  }

  return body.moves;
}

export function chooseDeterministicMove(moves, ply) {
  if (!moves || moves.length === 0) {
    return null;
  }

  const orderedMoves = moves.slice().sort((left, right) => {
    const leftKey = `${left.from}-${left.to}-${left.promotion || ''}`;
    const rightKey = `${right.from}-${right.to}-${right.promotion || ''}`;
    return leftKey.localeCompare(rightKey);
  });

  return orderedMoves[ply % orderedMoves.length];
}

export function submitMove(gameId, move) {
  if (!move) {
    check(move, { 'deterministic move exists': Boolean });
    return null;
  }

  const payload = {
    from: move.from,
    to: move.to,
    controller: 'HumanLocal',
  };

  if (move.promotion) {
    payload.promotion = move.promotion;
  }

  const response = http.post(`${BASE_URL}/games/${gameId}/moves`, JSON.stringify(payload), JSON_PARAMS);
  return checkedJson(response, 'submit move', 200);
}

export function fetchSessionState(sessionId, gameId) {
  const response = http.get(`${BASE_URL}/sessions/${sessionId}/state`, JSON_PARAMS);
  const body = checkedJson(response, 'fetch updated state', 200);

  check(body, {
    'updated state belongs to session and game': (json) =>
      Boolean(
        json &&
          json.session &&
          json.session.sessionId === sessionId &&
          json.game &&
          json.game.gameId === gameId
      ),
  });

  return body;
}

export function runPlayerLifecycle(config) {
  const plies = config.plies || 4;
  const thinkTimeSeconds = config.thinkTimeSeconds || 0.2;

  group('setup: create independent session', () => {
    const session = createSession();

    if (!session) {
      sleep(thinkTimeSeconds);
      return;
    }

    group('gameplay loop: legal moves -> move -> state', () => {
      for (let ply = 0; ply < plies; ply += 1) {
        const legalMoves = fetchLegalMoves(session.gameId);
        const selectedMove = chooseDeterministicMove(legalMoves, ply);

        sleep(thinkTimeSeconds);

        const moveResult = submitMove(session.gameId, selectedMove);
        if (!moveResult) {
          sleep(thinkTimeSeconds);
          return;
        }

        sleep(thinkTimeSeconds);

        const state = fetchSessionState(session.sessionId, session.gameId);
        if (!state || state.game.status !== 'Ongoing') {
          return;
        }

        sleep(thinkTimeSeconds);
      }
    });
  });
}
