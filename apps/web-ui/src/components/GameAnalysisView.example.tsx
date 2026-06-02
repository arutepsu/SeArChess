/**
 * Example integration of HeatmapPanel component.
 *
 * Shows how to use the heatmap statistics in a game analysis or statistics view.
 * This is a template demonstrating the integration pattern.
 */


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


