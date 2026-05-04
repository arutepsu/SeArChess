import { useNavigate } from "react-router-dom";
import { HeatmapPanel } from "./HeatmapPanel";
import { useHeatmapStats } from "../game/useHeatmapStats";
import "./GameAnalysisView.css";

type Props = {
    gameId: string | null;
};

export default function GameAnalysisView({ gameId }: Props) {
    const navigate = useNavigate();
    const whiteHeatmap = useHeatmapStats(gameId, "White");
    const blackHeatmap = useHeatmapStats(gameId, "Black");

    if (!gameId) {
        return (
            <main className="analysis-page">
                <section className="analysis-shell panel">
                    <header className="analysis-header">
                        <div>
                            <h1>Heatmap Analysis</h1>
                            <p>Start or resume a game first, then open the heatmap from the game screen.</p>
                        </div>
                        <button type="button" onClick={() => navigate("/game")}>
                            Back to Game
                        </button>
                    </header>
                </section>
            </main>
        );
    }

    return (
        <main className="analysis-page">
            <section className="analysis-shell panel">
                <header className="analysis-header">
                    <div>
                        <h1>Heatmap Analysis</h1>
                        <p>Captured squares and piece occupancy for the current game.</p>
                    </div>
                    <button type="button" onClick={() => navigate("/game")}>
                        Back to Game
                    </button>
                </header>

                <div className="analysis-grid">
                    <HeatmapPanel
                        heatmapData={whiteHeatmap.data}
                        loading={whiteHeatmap.loading}
                        error={whiteHeatmap.error}
                    />
                    <HeatmapPanel
                        heatmapData={blackHeatmap.data}
                        loading={blackHeatmap.loading}
                        error={blackHeatmap.error}
                    />
                </div>
            </section>
        </main>
    );
}
