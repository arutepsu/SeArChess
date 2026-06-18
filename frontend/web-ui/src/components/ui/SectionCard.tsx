import type { ReactNode } from "react";
import GlassPanel from "./GlassPanel";

interface SectionCardProps {
  title: string;
  children: ReactNode;
  className?: string;
}

export default function SectionCard({ title, children, className }: SectionCardProps) {
  return (
    <GlassPanel as="section" variant="default" className={["section-card", className].filter(Boolean).join(" ")}>
      <h2 className="section-card__title">{title}</h2>
      {children}
    </GlassPanel>
  );
}
