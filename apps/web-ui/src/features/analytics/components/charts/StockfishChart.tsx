import type { StockfishComparisonRow } from "../../../../api/analyticsTypes";
import EChart from "../EChart";
import { stockfishOption } from "../../model/chartOptions";

interface Props {
  rows: StockfishComparisonRow[];
}

export default function StockfishChart({ rows }: Props) {
  return (
    <>
      <EChart option={stockfishOption(rows)} height={Math.max(320, rows.length * 34 + 90)} empty={rows.length === 0} />
      {rows.length <= 1 && (
        <p className="analytics-chart-hint">
          Run a larger Stockfish tournament to compare multiple Stockfish variants.
        </p>
      )}
    </>
  );
}
