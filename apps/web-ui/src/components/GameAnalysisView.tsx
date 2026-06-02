import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { MoveHistoryEntryDto } from "../api/backendTypes";
import { getReplayFrame } from "../api/client";
import { mapGameSnapshotToGameState } from "../api/mapper";
import ChessBoard from "./ChessBoard";
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
    const [timelinePly, setTimelinePly] = useState(0);
    const [totalPlies, setTotalPlies] = useState(0);
    const [rawMoves, setRawMoves] = useState<MoveHistoryEntryDto[]>([]);
    const [replayBoard, setReplayBoard] = useState<ReturnType<typeof mapGameSnapshotToGameState> | null>(null);
    const [replayLoading, setReplayLoading] = useState(false);
    const [replayError, setReplayError] = useState<string | null>(null);

    useEffect(() => {
        setTimelinePly(0);
        setTotalPlies(0);
        setRawMoves([]);
        setReplayBoard(null);
        setReplayError(null);
    }, [gameId]);

    useEffect(() => {
        if (!gameId) return;

        let active = true;
        setReplayLoading(true);
        setReplayError(null);

        getReplayFrame(gameId, timelinePly)
            .then((frame) => {
                if (!active) return;
                setReplayBoard(mapGameSnapshotToGameState(frame.game));
                setRawMoves(frame.rawMoves);
                setTotalPlies(frame.totalPlies);
            })
            .catch((error) => {
                if (!active) return;
                const message = error instanceof Error ? error.message : "Replay frame could not be loaded.";
                setReplayError(message);
            })
            .finally(() => {
                if (active) setReplayLoading(false);
            });

        return () => {
            active = false;
        };
    }, [gameId, timelinePly]);

    const currentRawMove = useMemo(() => {
        if (timelinePly <= 0) return null;
        return rawMoves[timelinePly - 1] ?? null;
    }, [rawMoves, timelinePly]);

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
                        <p>Time-travel replay plus heatmap analytics for the current game.</p>
                    </div>
                    <button type="button" onClick={() => navigate("/game")}>
                        Back to Game
                    </button>
                </header>

                <section className="analysis-replay">
                    <div className="analysis-replay-header">
                        <h2>Time-Travel Replay</h2>
                        <p>Replay frame {timelinePly} of {totalPlies} using raw moves from MongoDB-backed game state.</p>
                    </div>

                    {replayError ? <div className="analysis-replay-error">{replayError}</div> : null}

                    {replayBoard ? (
                        <div className="analysis-replay-board">
                            <ChessBoard
                                board={replayBoard.board}
                                disabled={true}
                                idleAnimation={false}
                                onSelect={() => { }}
                                onAnimationFinished={() => { }}
                            />
                        </div>
                    ) : (
                        <div className="analysis-replay-loading">{replayLoading ? "Loading replay frame..." : "No replay frame available."}</div>
                    )}

                    <div className="analysis-timeline" aria-label="Replay timeline">
                        <input
                            type="range"
                            min={0}
                            max={totalPlies}
                            step={1}
                            value={timelinePly}
                            onChange={(event) => setTimelinePly(Number(event.currentTarget.value))}
                            disabled={replayLoading || totalPlies <= 0}
                        />
                        <div className="analysis-timeline-meta">
                            <span>Ply {timelinePly} / {totalPlies}</span>
                            <span>
                                {currentRawMove
                                    ? `${currentRawMove.from} -> ${currentRawMove.to}${currentRawMove.promotion ? ` (${currentRawMove.promotion})` : ""}`
                                    : "Initial position"}
                            </span>
                        </div>
                    </div>
                </section>

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
