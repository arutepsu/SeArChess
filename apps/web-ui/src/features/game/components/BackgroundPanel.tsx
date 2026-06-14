import type { GameSceneSkin } from "../sceneSkins";
import "./BackgroundPanel.css";

export type BackgroundOption = {
    id: string;
    label: string;
    url: string;
};

type Props = {
    backgrounds: BackgroundOption[];
    backgroundId: string;
    onChange: (id: string) => void;
    gameScenes?: GameSceneSkin[];
    gameSceneId?: string;
    onGameSceneChange?: (id: string) => void;
};

export default function BackgroundPanel({
    backgrounds,
    backgroundId,
    onChange,
    gameScenes,
    gameSceneId,
    onGameSceneChange,
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
                        className={`background-option${backgroundId === item.id ? " is-active" : ""}`}
                        onClick={() => onChange(item.id)}
                    >
                        <span style={{ backgroundImage: `url("${item.url}")` }} />
                        <small>{item.label}</small>
                    </button>
                ))}
            </div>

            {gameScenes && gameScenes.length > 0 && onGameSceneChange && (
                <>
                    <header className="scene-header">
                        <h3>Board Scene</h3>
                    </header>
                    <div className="background-grid">
                        {/* Classic — no scene image, restores the default CSS board */}
                        <button
                            type="button"
                            className={`background-option${!gameSceneId ? " is-active" : ""}`}
                            onClick={() => onGameSceneChange("")}
                        >
                            <span className="scene-classic-swatch" />
                            <small>Classic</small>
                        </button>

                        {gameScenes.map((scene) => (
                            <button
                                key={scene.id}
                                type="button"
                                className={`background-option${gameSceneId === scene.id ? " is-active" : ""}`}
                                onClick={() => onGameSceneChange(scene.id)}
                            >
                                <span style={{ backgroundImage: `url("${scene.imageUrl}")` }} />
                                <small>{scene.label}</small>
                            </button>
                        ))}
                    </div>
                </>
            )}
        </section>
    );
}
