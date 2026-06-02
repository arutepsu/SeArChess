/**
 * Example integration of HeatmapPanel component.
 *
 * Shows how to use the heatmap statistics in a game analysis or statistics view.
 * This is a template demonstrating the integration pattern.
 */

import React from "react";
import { HeatmapPanel } from "../components/HeatmapPanel";
import { useHeatmapStats } from "../game/useHeatmapStats";
import type { GameSnapshot } from "../api/backendTypes";

interface GameAnalysisViewProps {
    currentGame: GameSnapshot | null;
    sessionId: string | null;
}

/**
 * Example game statistics panel showing heatmap analysis.
 *
 * Can be used in:
 * - A dedicated statistics/analysis modal
 * - A game replay viewer
 * - A player statistics dashboard
 */
export function GameAnalysisView({
    currentGame,
    sessionId
}: GameAnalysisViewProps): JSX.Element {
    // Fetch heatmap for White player
    const whiteHeatmap = useHeatmapStats(sessionId, "White");

    // Fetch heatmap for Black player
    const blackHeatmap = useHeatmapStats(sessionId, "Black");

    if (!currentGame) {
        return <div>No game data available</div>;
    }

    return (
        <div className="game-analysis-container">
            <h2>Game Analysis - Move Heatmaps</h2>

            <div className="heatmap-grid">
                <div className="heatmap-section">
                    <h3>White Player Statistics</h3>
                    <HeatmapPanel
                        heatmapData={whiteHeatmap.data}
                        loading={whiteHeatmap.loading}
                        error={whiteHeatmap.error}
                    />
                </div>

                <div className="heatmap-section">
                    <h3>Black Player Statistics</h3>
                    <HeatmapPanel
                        heatmapData={blackHeatmap.data}
                        loading={blackHeatmap.loading}
                        error={blackHeatmap.error}
                    />
                </div>
            </div>

            <div className="analysis-info">
                <p>
                    💡 Tip: The heatmap shows where each player was most active.
                    Warmer colors (red) indicate more captures, cooler colors (blue)
                    indicate frequent piece placement.
                </p>
            </div>
        </div>
    );
}

/* Suggested CSS for the analysis view */
const analysisStyles = `
  .game-analysis-container {
    padding: 2rem;
    max-width: 1400px;
    margin: 0 auto;
  }

  .game-analysis-container h2 {
    margin-bottom: 1.5rem;
    color: #1a1a1a;
  }

  .heatmap-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(600px, 1fr));
    gap: 2rem;
    margin-bottom: 2rem;
  }

  .heatmap-section {
    flex: 1;
  }

  .heatmap-section h3 {
    margin-bottom: 1rem;
    color: #333;
    font-size: 1.1rem;
  }

  .analysis-info {
    background: #e3f2fd;
    border-left: 4px solid #2196f3;
    padding: 1rem;
    border-radius: 4px;
    color: #1565c0;
    font-size: 0.95rem;
  }

  @media (max-width: 1024px) {
    .heatmap-grid {
      grid-template-columns: 1fr;
    }
  }
`;
