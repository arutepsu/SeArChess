import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { SearchessAiComparisonRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: SearchessAiComparisonRow[];
}

export default function SearchessAiChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.score - a.score)
    .map((r) => ({
      label: r.opponentBotId,
      value: r.score,
      subLabel: `${r.opponentFamily} · ${(r.winRate * 100).toFixed(1)}%`,
    }));
  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
