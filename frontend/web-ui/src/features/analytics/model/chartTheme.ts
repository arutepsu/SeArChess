import type { EChartsOption } from "echarts";

const CHART_FONT = "'JungleAdventurer', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";

export const chartColors = {
  score: "#ffce74",
  wins: "#73d38b",
  draws: "#d4c6a2",
  losses: "#ef767a",
  rating: "#8fc7ff",
  secondary: "#c597ff",
  duration: "#f7a36f",
  grid: "rgba(248, 241, 230, 0.12)",
  text: "rgba(248, 241, 230, 0.78)",
  mutedText: "rgba(248, 241, 230, 0.52)",
  tooltipBg: "rgba(10, 14, 22, 0.96)",
  tooltipBorder: "rgba(255, 206, 116, 0.34)",
};

export function baseChartOption(): EChartsOption {
  return {
    backgroundColor: "transparent",
    color: [
      chartColors.score,
      chartColors.wins,
      chartColors.draws,
      chartColors.losses,
      chartColors.rating,
      chartColors.secondary,
      chartColors.duration,
    ],
    textStyle: {
      color: chartColors.text,
      fontFamily: CHART_FONT,
    },
    tooltip: {
      trigger: "axis",
      backgroundColor: chartColors.tooltipBg,
      borderColor: chartColors.tooltipBorder,
      borderWidth: 1,
      textStyle: { color: "#f8f1e6", fontFamily: CHART_FONT },
      axisPointer: {
        type: "shadow",
        shadowStyle: { color: "rgba(255, 255, 255, 0.06)" },
      },
    },
    legend: {
      top: 0,
      right: 0,
      textStyle: { color: chartColors.mutedText, fontFamily: CHART_FONT },
      icon: "roundRect",
    },
    grid: {
      top: 42,
      right: 24,
      bottom: 24,
      left: 12,
      containLabel: true,
    },
  };
}

export function valueAxis(name?: string) {
  return {
    type: "value" as const,
    name,
    nameTextStyle: { color: chartColors.mutedText, fontFamily: CHART_FONT },
    axisLabel: { color: chartColors.mutedText, fontFamily: CHART_FONT },
    axisLine: { lineStyle: { color: chartColors.grid } },
    splitLine: { lineStyle: { color: chartColors.grid } },
  };
}

export function categoryAxis(data: string[], inverse = false) {
  return {
    type: "category" as const,
    data,
    inverse,
    axisLabel: {
      color: chartColors.text,
      fontFamily: CHART_FONT,
      width: 150,
      overflow: "truncate" as const,
    },
    axisLine: { lineStyle: { color: chartColors.grid } },
    axisTick: { show: false },
  };
}
