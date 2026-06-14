import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { BotFamilyRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: BotFamilyRow[];
}

export default function BotFamilyChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.totalScore - a.totalScore)
    .map((r) => ({
      label: r.family,
      value: r.totalScore,
      subLabel: `${(r.winRate * 100).toFixed(1)}%`,
    }));
  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
