import React, { useState } from "react";
import type { HeatmapResponse } from "../api/backendTypes";
import "./HeatmapPanel.css";

interface HeatmapPanelProps {
    heatmapData: HeatmapResponse | null;
    loading: boolean;
    error: Error | null;
}

type MetricType = "captures" | "occupancy";

/**
 * Heatmap visualization component showing board statistics.
 *
 * Renders an 8x8 chessboard overlay with color gradients indicating:
 * - Capture intensity (red = hot spots for captures)
 * - Occupancy frequency (blue = frequently visited squares)
 *
 * Features:
 * - Toggle between metrics
 * - Color gradient visualization
 * - Summary statistics
 */
export function HeatmapPanel({
    heatmapData,
    loading,
    error
}: HeatmapPanelProps): JSX.Element {
    const [metric, setMetric] = useState<MetricType>("occupancy");

    if (error) {
        return (
            <div className="heatmap-panel heatmap-error">
                <p>Error loading heatmap: {error.message}</p>
            </div>
        );
    }

    if (loading) {
        return (
            <div className="heatmap-panel heatmap-loading">
                <p>Loading statistics...</p>
            </div>
        );
    }

    if (!heatmapData) {
        return (
            <div className="heatmap-panel heatmap-empty">
                <p>No heatmap data available</p>
            </div>
        );
    }

    const squaresByPosition = new Map(
        heatmapData.statistics.map(stat => [stat.square, stat])
    );

    // Render board in rank-file order (standard chess board view)
    // a1, b1, c1, ..., h1 (bottom row)
    // a2, b2, c2, ..., h2
    // ...
    // a8, b8, c8, ..., h8 (top row)
    const files = ["a", "b", "c", "d", "e", "f", "g", "h"];
    const ranks = [8, 7, 6, 5, 4, 3, 2, 1];

    return (
        <div className="heatmap-panel">
            <div className="heatmap-header">
                <h3>Heatmap Statistics</h3>
                <div className="heatmap-meta">
                    <span className="player-info">
                        {heatmapData.playerColor} Player
                    </span>
                    <span className="games-info">
                        {heatmapData.gamesAnalyzed} game{heatmapData.gamesAnalyzed !== 1 ? "s" : ""} analyzed
                    </span>
                </div>
            </div>

            <div className="heatmap-controls">
                <div className="metric-toggle">
                    <label>
                        <input
                            type="radio"
                            value="occupancy"
                            checked={metric === "occupancy"}
                            onChange={e => setMetric(e.target.value as MetricType)}
                        />
                        Occupancy Frequency
                    </label>
                    <label>
                        <input
                            type="radio"
                            value="captures"
                            checked={metric === "captures"}
                            onChange={e => setMetric(e.target.value as MetricType)}
                        />
                        Capture Intensity
                    </label>
                </div>
            </div>

            <div className="heatmap-board-container">
                <div className="heatmap-board">
                    {ranks.map(rank =>
                        files.map(file => {
                            const square = `${file}${rank}`;
                            const squareData = squaresByPosition.get(square);
                            if (!squareData) return null;

                            const score =
                                metric === "occupancy"
                                    ? squareData.normalizedOccupancyScore
                                    : squareData.normalizedCaptureScore;

                            const intensity = Math.max(0, Math.min(1, score));
                            const backgroundColor = getHeatmapColor(intensity, metric);

                            return (
                                <div
                                    key={square}
                                    className="heatmap-square"
                                    style={{ backgroundColor }}
                                    title={`${square}: ${metric === "occupancy" ? squareData.occupancyCount + " visits" : squareData.captureCount + " captures"}`}
                                >
                                    <span className="square-label">{square}</span>
                                </div>
                            );
                        })
                    )}
                </div>
            </div>

            <div className="heatmap-legend">
                <div className="legend-row">
                    <span className="legend-label">Min</span>
                    <div className="legend-gradient" />
                    <span className="legend-label">Max</span>
                </div>
                <div className="legend-stats">
                    {metric === "occupancy" ? (
                        <p>
                            Max visits: <strong>{heatmapData.maxOccupancyCount}</strong>
                        </p>
                    ) : (
                        <p>
                            Max captures: <strong>{heatmapData.maxCaptureCount}</strong>
                        </p>
                    )}
                </div>
            </div>
        </div>
    );
}

/**
 * Generate HSL color based on intensity and metric type.
 *
 * Occupancy: Blue gradient (cool)
 * Captures: Red gradient (hot)
 *
 * @param intensity - normalized value 0.0 to 1.0
 * @param metric - which metric to visualize
 * @returns HSL color string
 */
function getHeatmapColor(intensity: number, metric: MetricType): string {
    if (intensity === 0) {
        return "hsl(0, 0%, 95%)"; // nearly white
    }

    if (metric === "occupancy") {
        // Blue gradient: light blue to dark blue
        const hue = 210; // blue
        const saturation = Math.round(30 + intensity * 70);
        const lightness = Math.round(85 - intensity * 40);
        return `hsl(${hue}, ${saturation}%, ${lightness}%)`;
    } else {
        // Red gradient: light orange to dark red
        const hue = Math.round(0 - intensity * 30); // red to orange-red
        const saturation = Math.round(50 + intensity * 50);
        const lightness = Math.round(80 - intensity * 35);
        return `hsl(${hue}, ${saturation}%, ${lightness}%)`;
    }
}
