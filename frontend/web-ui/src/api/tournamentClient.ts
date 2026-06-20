import type {
  BotSummary,
  AnalyzeTournamentResponse,
  CreateTournamentRequest,
  CreateTournamentResponse,
  TournamentJobDetails,
  TournamentJobSummary,
} from "./tournamentTypes";
import { authHeaders } from "./client";

const tournamentBase =
  (import.meta.env.VITE_TOURNAMENT_API_BASE_URL?.trim() ?? "");

async function withAuth(init?: RequestInit): Promise<RequestInit> {
  const headers = new Headers(init?.headers);
  const auth = await authHeaders();
  for (const [key, value] of Object.entries(auth)) {
    headers.set(key, value);
  }
  return { ...init, headers };
}

async function fetchTournament<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${tournamentBase}${path}`, await withAuth(init));

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error("You are not logged in, or your session token is missing.");
    }
    const text = await response.text().catch(() => "");
    let message = `Tournament request failed: ${response.status}`;
    try {
      const json = JSON.parse(text) as { message?: string };
      if (json.message) message = json.message;
    } catch {
      // ignore parse error
    }
    throw new Error(message);
  }

  return (await response.json()) as T;
}
const TOURNAMENT_ROUTE = "/api/tournaments";

export async function fetchTournamentBots(): Promise<BotSummary[]> {
  const data = await fetchTournament<{ bots: BotSummary[] }>("/api/tournaments/bots");
  return data.bots;
}

export async function listTournamentJobs(): Promise<TournamentJobSummary[]> {
  const data = await fetchTournament<{ jobs: TournamentJobSummary[] }>(TOURNAMENT_ROUTE);
  return data.jobs;
}

export async function createTournamentJob(request: CreateTournamentRequest): Promise<CreateTournamentResponse> {
  return fetchTournament<CreateTournamentResponse>(TOURNAMENT_ROUTE, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
}

export async function fetchTournamentJob(jobId: string): Promise<TournamentJobDetails> {
  return fetchTournament<TournamentJobDetails>(
    `${TOURNAMENT_ROUTE}/${encodeURIComponent(jobId)}`
  );
}

export async function cancelTournamentJob(jobId: string): Promise<TournamentJobDetails> {
  return fetchTournament<TournamentJobDetails>(
    `${TOURNAMENT_ROUTE}/${encodeURIComponent(jobId)}/cancel`,
    {
      method: "POST",
    }
  );
}

export async function analyzeTournament(
  jobId: string,
  outputPath?: string
): Promise<AnalyzeTournamentResponse> {
  return fetchTournament<AnalyzeTournamentResponse>(
    `${TOURNAMENT_ROUTE}/${encodeURIComponent(jobId)}/analyze`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(outputPath ? { outputPath } : {}),
    }
  );
}