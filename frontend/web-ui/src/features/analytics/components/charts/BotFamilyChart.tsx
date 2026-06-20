import type { BotFamilyRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { botFamilyOption } from "../../model/chartOptions";

interface Props {
  rows: BotFamilyRow[];
}

export default function BotFamilyChart({ rows }: Props) {
  return <EChart option={botFamilyOption(rows)} height={Math.max(320, rows.length * 38 + 96)} empty={rows.length === 0} />;
}
