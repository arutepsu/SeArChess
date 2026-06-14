import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { ColorPerformanceRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: ColorPerformanceRow[];
}

export default function ColorPerformanceChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => (b.whiteScore + b.blackScore) - (a.whiteScore + a.blackScore))
    .map((r) => ({
      label: r.botId,
      value: r.whiteScore + r.blackScore,
      subLabel: `W ${r.whiteScore.toFixed(1)} / B ${r.blackScore.toFixed(1)}`,
    }));

  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
