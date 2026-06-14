import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { FastestWinRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: FastestWinRow[];
}

export default function FastestWinsChart({ rows }: Props) {
  const sorted = [...rows].sort((a, b) => a.avgWinPly - b.avgWinPly);
  const max = sorted.length > 0 ? Math.max(...sorted.map((r) => r.avgWinPly)) : undefined;
  const items = sorted.map((r) => ({
    label: r.winnerBotId,
    value: r.avgWinPly,
    subLabel: `lower is faster - min ${r.minWinPly}`,
  }));

  return <HorizontalBarChart items={items} maxValue={max} formatValue={(v) => v.toFixed(1)} />;
}
