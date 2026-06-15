import { createElement, forwardRef } from "react";
import type { HTMLAttributes, ReactNode } from "react";
import "./GlassPanel.css";

export type GlassPanelVariant = "default" | "strong" | "subtle";
type GlassPanelElement = "div" | "section" | "article";

export type GlassPanelProps = {
  children: ReactNode;
  className?: string;
  variant?: GlassPanelVariant;
  as?: GlassPanelElement;
} & Omit<HTMLAttributes<HTMLElement>, "className">;

const panelElements: Record<GlassPanelElement, GlassPanelElement> = {
  div: "div",
  section: "section",
  article: "article",
};

const GlassPanel = forwardRef<HTMLElement, GlassPanelProps>(function GlassPanel(
  {
    children,
    className,
    variant = "default",
    as: Component = "div",
    ...panelProps
  },
  ref
) {
  const panelClassName = ["glass-panel", `glass-panel--${variant}`, className].filter(Boolean).join(" ");

  return createElement(
    panelElements[Component],
    {
      ...panelProps,
      ref,
      className: panelClassName,
    },
    children
  );
});

export default GlassPanel;
