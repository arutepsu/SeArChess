export interface AnalyticsRunSummary {
  runId: string;
  sourcePath: string;
  createdAt: string;
}

export interface LeaderboardRow {
  botId: string;
  totalScore: number;
  wins: number;
  draws: number;
  losses: number;
  gamesPlayed: number;
  avgPly: number;
  winRate: number;
}

export interface BotFamilyRow {
  family: string;
  games: number;
  wins: number;
  losses: number;
  draws: number;
  totalScore: number;
  winRate: number;
}

export interface StrategyRow {
  strategyType: string;
  games: number;
  wins: number;
  losses: number;
  draws: number;
  totalScore: number;
  winRate: number;
}

export interface SearchessAiComparisonRow {
  opponentBotId: string;
  opponentFamily: string;
  games: number;
  searchessAiWins: number;
  draws: number;
  losses: number;
  score: number;
  avgGameLength: number;
  winRate: number;
}

export interface StockfishComparisonRow {
  botId: string;
  strategyType: string;
  games: number;
  wins: number;
  draws: number;
  totalScore: number;
  avgGameLength: number;
  winRate: number;
}

export interface AvgGameLengthRow {
  whiteBotId: string;
  blackBotId: string;
  gamesPlayed: number;
  avgTotalPly: number;
  avgDurationMs: number;
}

export interface EloRatingsRow {
  botId: string;
  rating: number;
  ratingChange: number;
  gamesPlayed: number;
  wins: number;
  draws: number;
  losses: number;
  averageOpponentRating: number;
  lastGameTimestamp: string;
}

export interface TerminationReasonRow {
  terminationReason: string;
  count: number;
}

export interface ColorPerformanceRow {
  botId: string;
  gamesAsWhite: number;
  whiteWins: number;
  whiteScore: number;
  gamesAsBlack: number;
  blackWins: number;
  blackScore: number;
}

export interface FastestWinRow {
  winnerBotId: string;
  decisiveGames: number;
  avgWinPly: number;
  minWinPly: number;
  avgWinDurationMs: number;
}

export interface AnalyticsUnavailableError {
  code: "ANALYTICS_UNAVAILABLE";
  message: string;
}
