import type { FastestWinRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { fastestWinsOption } from "../../model/chartOptions";

interface Props {
  rows: FastestWinRow[];
}

export default function FastestWinsChart({ rows }: Props) {
  return <EChart option={fastestWinsOption(rows)} height={Math.max(320, rows.length * 34 + 90)} empty={rows.length === 0} />;
}
