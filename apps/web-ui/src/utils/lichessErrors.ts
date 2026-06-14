/**
 * Extract a structured error code from a Lichess API error.
 * Tries JSON parse first, then falls back to a regex search over known codes.
 */
function extractCode(error: unknown, knownCodes: RegExp): string {
  const raw = error instanceof Error ? error.message : String(error);
  const trimmed = raw.trim();
  try {
    const parsed = JSON.parse(raw) as { code?: unknown };
    if (typeof parsed.code === "string") return parsed.code;
  } catch {
    const match = trimmed.match(knownCodes);
    if (match !== null) return match[1];
  }
  return trimmed;
}

const COMMON_CODES =
  /\b(NO_LICHESS_LINK|NO_CHALLENGE_READY_CAPABILITY|NO_LICHESS_GAME_CAPABILITY|NO_STORED_LICHESS_TOKEN|TOKEN_ENCRYPTION_NOT_CONFIGURED|LICHESS_TOKEN_EXPIRED)\b/;

const COMMON_MESSAGES: Record<string, string> = {
  NO_LICHESS_LINK:                "Link your Lichess account first.",
  NO_CHALLENGE_READY_CAPABILITY:  "Upgrade your Lichess permissions first.",
  NO_LICHESS_GAME_CAPABILITY:     "Upgrade your Lichess permissions before viewing this game in Searchess.",
  NO_STORED_LICHESS_TOKEN:        "Your Lichess authorization is incomplete. Please re-authorize.",
  TOKEN_ENCRYPTION_NOT_CONFIGURED:"The server is not configured for Lichess integration yet.",
  LICHESS_TOKEN_EXPIRED:          "Your Lichess authorization expired. Please re-authorize.",
};

export function gameStateErrorMessage(error: unknown): string {
  const code = extractCode(
    error,
    new RegExp(
      COMMON_CODES.source.replace(")\\b", "|INVALID_LICHESS_GAME_ID|LICHESS_GAME_STATE_FAILED)\\b")
    )
  );
  return (
    COMMON_MESSAGES[code] ??
    {
      INVALID_LICHESS_GAME_ID:  "The Lichess game id is invalid.",
      LICHESS_GAME_STATE_FAILED:"Could not load the Lichess game state.",
    }[code] ??
    "Could not load the Lichess game state."
  );
}

export function moveSubmitErrorMessage(error: unknown): string {
  const code = extractCode(
    error,
    new RegExp(
      COMMON_CODES.source.replace(
        ")\\b",
        "|INVALID_MOVE_FORMAT|NOT_USER_TURN|ILLEGAL_OR_INVALID_MOVE|LICHESS_MOVE_FAILED)\\b"
      )
    )
  );
  return (
    COMMON_MESSAGES[code] ??
    {
      INVALID_MOVE_FORMAT:   "Use UCI move format, for example e2e4 or e7e8q.",
      NOT_USER_TURN:         "It is not your turn.",
      ILLEGAL_OR_INVALID_MOVE:"Lichess rejected this move. Check that it is legal and your turn.",
      LICHESS_MOVE_FAILED:   "Could not submit the move to Lichess.",
      NO_CHALLENGE_READY_CAPABILITY: "Upgrade your Lichess permissions before playing from Searchess.",
    }[code] ??
    "Could not submit the move to Lichess."
  );
}

export function challengeErrorMessage(error: unknown): string {
  const code = extractCode(
    error,
    new RegExp(
      COMMON_CODES.source.replace(
        ")\\b",
        "|INVALID_CHALLENGE_REQUEST|LICHESS_CHALLENGE_FAILED)\\b"
      )
    )
  );
  return (
    COMMON_MESSAGES[code] ??
    {
      INVALID_CHALLENGE_REQUEST:"The challenge settings are invalid.",
      LICHESS_CHALLENGE_FAILED: "Could not create the Lichess challenge.",
      NO_CHALLENGE_READY_CAPABILITY: "Upgrade your Lichess permissions before creating a challenge.",
    }[code] ??
    "Could not create the Lichess challenge."
  );
}
