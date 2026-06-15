import ReactECharts from "echarts-for-react";
import type { EChartsOption } from "echarts";
import "./EChart.css";

export type EChartProps = {
  option: EChartsOption;
  height?: number | string;
  loading?: boolean;
  empty?: boolean;
  emptyMessage?: string;
  className?: string;
};

export default function EChart({
  option,
  height = 320,
  loading = false,
  empty = false,
  emptyMessage = "No chart data available.",
  className,
}: EChartProps) {
  const styleHeight = typeof height === "number" ? `${height}px` : height;
  const classes = ["analytics-echart", className].filter(Boolean).join(" ");

  if (empty) {
    return (
      <div className={`${classes} analytics-echart--empty`} style={{ minHeight: styleHeight }}>
        {emptyMessage}
      </div>
    );
  }

  return (
    <div className={classes} style={{ minHeight: styleHeight }}>
      <ReactECharts
        option={option}
        style={{ width: "100%", height: styleHeight }}
        showLoading={loading}
        notMerge
        lazyUpdate
      />
    </div>
  );
}
