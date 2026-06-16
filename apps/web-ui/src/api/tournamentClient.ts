import type {
  BotSummary,
  AnalyzeTournamentResponse,
  CreateTournamentRequest,
  CreateTournamentResponse,
  TournamentJobDetails,
  TournamentJobSummary,
} from "./tournamentTypes";

const tournamentBase =
  (import.meta.env.VITE_TOURNAMENT_API_BASE_URL?.trim() ?? "");

async function fetchTournament<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${tournamentBase}${path}`, init);

  if (!response.ok) {
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

export async function fetchTournamentBots(): Promise<BotSummary[]> {
  const data = await fetchTournament<{ bots: BotSummary[] }>("/api/tournaments/bots");
  return data.bots;
}

export async function listTournamentJobs(): Promise<TournamentJobSummary[]> {
  const data = await fetchTournament<{ jobs: TournamentJobSummary[] }>("/api/tournaments");
  return data.jobs;
}

export async function createTournamentJob(request: CreateTournamentRequest): Promise<CreateTournamentResponse> {
  return fetchTournament<CreateTournamentResponse>("/api/tournaments", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
}

export async function fetchTournamentJob(jobId: string): Promise<TournamentJobDetails> {
  return fetchTournament<TournamentJobDetails>(`/api/tournaments/${encodeURIComponent(jobId)}`);
}

export async function cancelTournamentJob(jobId: string): Promise<TournamentJobDetails> {
  return fetchTournament<TournamentJobDetails>(`/api/tournaments/${encodeURIComponent(jobId)}/cancel`, {
    method: "POST",
  });
}

export async function analyzeTournament(jobId: string, outputPath?: string): Promise<AnalyzeTournamentResponse> {
  return fetchTournament<AnalyzeTournamentResponse>(`/api/tournaments/${encodeURIComponent(jobId)}/analyze`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(outputPath ? { outputPath } : {}),
  });
}
