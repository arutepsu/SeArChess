import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { StrategyRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: StrategyRow[];
}

export default function StrategyChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.totalScore - a.totalScore)
    .map((r) => ({
      label: r.strategyType,
      value: r.totalScore,
      subLabel: `${(r.winRate * 100).toFixed(1)}%`,
    }));
  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
