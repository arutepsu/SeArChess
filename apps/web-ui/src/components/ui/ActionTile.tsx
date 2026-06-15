import type { ReactNode } from "react";
import "./ActionTile.css";

export type ActionTileVariant = "default" | "primary" | "locked" | "danger";

export type ActionTileProps = {
  label: string;
  description?: string;
  badge?: string;
  disabled?: boolean;
  disabledReason?: string;
  selected?: boolean;
  variant?: ActionTileVariant;
  onClick?: () => void;
  className?: string;
  children?: ReactNode;
  ariaLabel?: string;
};

export default function ActionTile({
  label,
  description,
  badge,
  disabled = false,
  disabledReason,
  selected = false,
  variant = "default",
  onClick,
  className,
  children,
  ariaLabel,
}: ActionTileProps) {
  const effectiveVariant = disabled && variant === "default" ? "locked" : variant;
  const helperText = disabled && disabledReason ? disabledReason : description;

  return (
    <button
      type="button"
      className={[
        "action-tile",
        `action-tile--${effectiveVariant}`,
        selected ? "action-tile--selected" : "",
        disabled ? "action-tile--disabled" : "",
        children ? "action-tile--with-media" : "",
        className,
      ].filter(Boolean).join(" ")}
      disabled={disabled}
      aria-disabled={disabled}
      aria-pressed={selected || undefined}
      aria-label={ariaLabel}
      onClick={disabled ? undefined : onClick}
    >
      {children ? <span className="action-tile__media">{children}</span> : null}
      <span className="action-tile__body">
        <span className="action-tile__header">
          <span className="action-tile__label">{label}</span>
          {badge ? <span className="action-tile__badge">{badge}</span> : null}
        </span>
        {helperText ? (
          <span className={disabled && disabledReason ? "action-tile__disabled-reason" : "action-tile__description"}>
            {helperText}
          </span>
        ) : null}
      </span>
    </button>
  );
}
