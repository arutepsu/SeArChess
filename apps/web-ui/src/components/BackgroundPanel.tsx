export type BackgroundOption = {
    id: string;
    label: string;
    url: string;
};

type Props = {
    backgrounds: BackgroundOption[];
    backgroundId: string;
    onChange: (id: string) => void;
};

export default function BackgroundPanel({
    backgrounds,
    backgroundId,
    onChange
}: Props) {
    return (
        <section className="panel background-panel">
            <header>
                <h2>Background</h2>
                <p>Pick the arena for your next battle.</p>
            </header>

            <div className="background-grid">
                {backgrounds.map((item) => (
                    <button
                        key={item.id}
                        type="button"
                        className={`background-option${backgroundId === item.id ? " is-active" : ""
                            }`}
                        onClick={() => onChange(item.id)}
                    >
                        <span style={{ backgroundImage: `url("${item.url}")` }} />
                        <small>{item.label}</small>
                    </button>
                ))}
            </div>
        </section>
    );
}
