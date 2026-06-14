import HorizontalBarChart from "../../../../components/ui/HorizontalBarChart";
import type { TerminationReasonRow } from "../../../../api/analyticsTypes";

interface Props {
  rows: TerminationReasonRow[];
}

export default function TerminationsChart({ rows }: Props) {
  const items = [...rows]
    .sort((a, b) => b.count - a.count)
    .map((r) => ({
      label: r.terminationReason,
      value: r.count,
    }));

  return <HorizontalBarChart items={items} formatValue={(v) => Math.round(v).toString()} />;
}
