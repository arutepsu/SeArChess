export type TournamentJobStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";
export type TournamentAnalysisStatus = "not_requested" | "queued" | "running" | "succeeded" | "failed";

export interface BotSummary {
  botId: string;
  displayName: string;
  family: string;
  strategyType: string;
  engineType: string;
  modelVersion: string;
  available: boolean;
  unavailableReason: string | null;
}

export interface CreateTournamentRequest {
  name?: string;
  botIds: string[];
  mode: "double-round-robin";
  repetitions: number;
  maxPly: number;
  seed?: number;
}

export interface CreateTournamentResponse {
  jobId: string;
  status: TournamentJobStatus;
  createdAt: string;
  statusUrl: string;
}

export interface TournamentJobSummary {
  jobId: string;
  name: string | null;
  status: TournamentJobStatus;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  selectedBotIds: string[];
  mode: string;
  plannedGames: number;
  completedGames: number;
}

export interface TournamentJobDetails extends TournamentJobSummary {
  repetitions: number;
  maxPly: number;
  outputPath: string | null;
  errorMessage: string | null;
  analysisStatus: TournamentAnalysisStatus;
  analyticsRunId: string | null;
  analyticsOutputPath: string | null;
  analyticsErrorMessage: string | null;
  analyzeUrl: string | null;
  eventsUrl: string | null;
  resultSummary: string | null;
}

export interface AnalyzeTournamentResponse {
  jobId: string;
  analysisStatus: TournamentAnalysisStatus;
  analyticsOutputPath: string | null;
  statusUrl: string;
}

export interface TournamentApiError {
  code: string;
  message: string;
}
