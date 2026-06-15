import type { AvgGameLengthRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { avgGameLengthOption } from "../../model/chartOptions";

interface Props {
  rows: AvgGameLengthRow[];
}

export default function AvgGameLengthChart({ rows }: Props) {
  const shownRows = Math.min(rows.length, 10);
  return <EChart option={avgGameLengthOption(rows)} height={Math.max(280, shownRows * 38 + 96)} empty={rows.length === 0} />;
}
