import { useEffect, useState, useCallback } from "react";
import type { HeatmapResponse } from "../api/backendTypes";
import { getHeatmapStats } from "../api/client";

interface UseHeatmapStatsResult {
    data: HeatmapResponse | null;
    loading: boolean;
    error: Error | null;
    refresh: () => Promise<void>;
}

/**
 * Hook for fetching and managing heatmap statistics.
 *
 * Loads aggregated board statistics (capture intensity and occupancy frequency)
 * for a specific player in a game. The data comes pre-aggregated from the backend
 * for fast rendering.
 *
 * @param gameId - UUID of the game session to analyze
 * @param playerColor - "White" or "Black"
 * @returns Object with heatmap data, loading state, error, and refresh function
 */
export function useHeatmapStats(
    gameId: string | null,
    playerColor: "White" | "Black"
): UseHeatmapStatsResult {
    const [data, setData] = useState<HeatmapResponse | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);

    const fetchHeatmapStats = useCallback(async () => {
        if (!gameId) {
            setData(null);
            return;
        }

        setLoading(true);
        setError(null);

        try {
            const response = await getHeatmapStats(gameId, playerColor);
            setData(response);
        } catch (err) {
            const errorObj = err instanceof Error ? err : new Error(String(err));
            setError(errorObj);
        } finally {
            setLoading(false);
        }
    }, [gameId, playerColor]);

    // Fetch on mount and when dependencies change
    useEffect(() => {
        fetchHeatmapStats();
    }, [fetchHeatmapStats]);

    const refresh = useCallback(async () => {
        await fetchHeatmapStats();
    }, [fetchHeatmapStats]);

    return { data, loading, error, refresh };
}
