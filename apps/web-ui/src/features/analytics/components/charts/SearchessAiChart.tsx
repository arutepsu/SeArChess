import type { SearchessAiComparisonRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { searchessAiOption } from "../../model/chartOptions";

interface Props {
  rows: SearchessAiComparisonRow[];
}

export default function SearchessAiChart({ rows }: Props) {
  return <EChart option={searchessAiOption(rows)} height={Math.max(260, rows.length * 34 + 90)} empty={rows.length === 0} />;
}
