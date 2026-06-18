import type { GameBackground } from "../backgroundSkins";

type Props = {
  backgrounds: GameBackground[];
  backgroundId: string;
  onBackgroundChange: (id: string) => void;
};

export default function AppBackgroundPanel({
  backgrounds,
  backgroundId,
  onBackgroundChange,
}: Props) {
  return (
    <section className="background-panel">
      <header>
        <h3>Background</h3>
      </header>
      <div className="background-grid">
        {backgrounds.map((bg) => (
          <button
            key={bg.id}
            type="button"
            className={`background-option${backgroundId === bg.id ? " is-active" : ""}`}
            onClick={() => onBackgroundChange(bg.id)}
          >
            <span style={{ backgroundImage: `url("${bg.imageUrl}")` }} />
            <small>{bg.label}</small>
          </button>
        ))}
      </div>
    </section>
  );
}
