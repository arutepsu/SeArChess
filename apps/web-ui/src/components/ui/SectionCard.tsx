interface SectionCardProps {
  title: string;
  children: React.ReactNode;
  className?: string;
}

export default function SectionCard({ title, children, className }: SectionCardProps) {
  return (
    <div className={["section-card", className].filter(Boolean).join(" ")}>
      <h2 className="section-card__title">{title}</h2>
      {children}
    </div>
  );
}
