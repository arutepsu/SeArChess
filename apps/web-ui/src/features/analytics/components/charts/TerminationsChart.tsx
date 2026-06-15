import type { TerminationReasonRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { terminationsOption } from "../../model/chartOptions";

interface Props {
  rows: TerminationReasonRow[];
}

export default function TerminationsChart({ rows }: Props) {
  return <EChart option={terminationsOption(rows)} height={Math.max(260, rows.length * 34 + 90)} empty={rows.length === 0} />;
}
