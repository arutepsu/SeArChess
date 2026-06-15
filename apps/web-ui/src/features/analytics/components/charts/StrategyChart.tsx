import type { StrategyRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { strategyOption } from "../../model/chartOptions";

interface Props {
  rows: StrategyRow[];
}

export default function StrategyChart({ rows }: Props) {
  return <EChart option={strategyOption(rows)} height={Math.max(260, rows.length * 38 + 96)} empty={rows.length === 0} />;
}
