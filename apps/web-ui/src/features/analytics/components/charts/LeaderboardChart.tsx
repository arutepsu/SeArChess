import type { LeaderboardRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { leaderboardOption } from "../../model/chartOptions";

interface Props {
  rows: LeaderboardRow[];
}

export default function LeaderboardChart({ rows }: Props) {
  return <EChart option={leaderboardOption(rows)} height={Math.max(260, rows.length * 34 + 90)} empty={rows.length === 0} />;
}
