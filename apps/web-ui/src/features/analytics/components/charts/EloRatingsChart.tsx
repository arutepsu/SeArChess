import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { EloRatingsRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: EloRatingsRow[];
}

function signed(value: number): string {
  return value >= 0 ? `+${value.toFixed(1)}` : value.toFixed(1);
}

export default function EloRatingsChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.rating - a.rating)
    .map((r) => ({
      label: r.botId,
      value: r.rating,
      subLabel: signed(r.ratingChange),
    }));

  return <HorizontalBarChart items={items} formatValue={(v) => v.toFixed(1)} />;
}
