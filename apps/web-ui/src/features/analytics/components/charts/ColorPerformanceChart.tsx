import type { ColorPerformanceRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { colorPerformanceOption } from "../../model/chartOptions";

interface Props {
  rows: ColorPerformanceRow[];
}

export default function ColorPerformanceChart({ rows }: Props) {
  return <EChart option={colorPerformanceOption(rows)} height={320} empty={rows.length === 0} />;
}
