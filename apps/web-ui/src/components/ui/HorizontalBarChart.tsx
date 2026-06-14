import "./HorizontalBarChart.css";

export interface BarItem {
  label: string;
  value: number;
  subLabel?: string;
}

interface HorizontalBarChartProps {
  items: BarItem[];
  formatValue?: (v: number) => string;
  maxValue?: number;
}

export default function HorizontalBarChart({
  items,
  formatValue,
  maxValue,
}: HorizontalBarChartProps) {
  if (items.length === 0) return null;

  const max = maxValue ?? Math.max(...items.map((i) => i.value));
  if (max <= 0) return null;

  const fmt = formatValue ?? ((v: number) => v.toFixed(1));

  return (
    <div className="h-bar-chart" role="img" aria-label="Bar chart">
      {items.map((item, idx) => {
        const pct = Math.max(0, Math.min(100, (item.value / max) * 100));
        return (
          <div key={`${idx}-${item.label}`} className="h-bar-row">
            <div className="h-bar-label-cell">
              <span className="h-bar-label" title={item.label}>
                {item.label}
              </span>
              {item.subLabel && (
                <span className="h-bar-sublabel">{item.subLabel}</span>
              )}
            </div>
            <div className="h-bar-track">
              <div className="h-bar-fill" style={{ width: `${pct}%` }} />
            </div>
            <span className="h-bar-value">{fmt(item.value)}</span>
          </div>
        );
      })}
    </div>
  );
}
