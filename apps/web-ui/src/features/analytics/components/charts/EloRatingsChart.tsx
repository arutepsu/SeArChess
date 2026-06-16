import type { EloRatingsRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { eloRatingsOption } from "../../model/chartOptions";

interface Props {
  rows: EloRatingsRow[];
}

export default function EloRatingsChart({ rows }: Props) {
  return <EChart option={eloRatingsOption(rows)} height={Math.max(320, rows.length * 34 + 90)} empty={rows.length === 0} />;
}
