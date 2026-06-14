import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { LeaderboardRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: LeaderboardRow[];
}

export default function LeaderboardChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.totalScore - a.totalScore)
    .map((r) => ({
      label: r.botId,
      value: r.totalScore,
      subLabel: `${(r.winRate * 100).toFixed(1)}%`,
    }));
  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
