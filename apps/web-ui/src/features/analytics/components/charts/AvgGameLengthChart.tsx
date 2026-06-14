import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { AvgGameLengthRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: AvgGameLengthRow[];
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

export default function AvgGameLengthChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.avgTotalPly - a.avgTotalPly)
    .slice(0, 10)
    .map((r) => ({
      label: `${r.whiteBotId} vs ${r.blackBotId}`,
      value: r.avgTotalPly,
      subLabel: formatMs(r.avgDurationMs),
    }));
  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
