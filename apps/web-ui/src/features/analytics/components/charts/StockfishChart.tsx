import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { StockfishComparisonRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: StockfishComparisonRow[];
}

export default function StockfishChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.totalScore - a.totalScore)
    .map((r) => ({
      label: r.botId,
      value: r.totalScore,
      subLabel: `${r.strategyType} · ${(r.winRate * 100).toFixed(1)}%`,
    }));
  return (
    <>
      <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />
      {rows.length <= 1 && (
        <p className="analytics-chart-hint">
          Run a larger Stockfish tournament to compare multiple Stockfish variants.
        </p>
      )}
    </>
  );
}
