import type { GameSceneSkin } from "../sceneSkins";
import "./BackgroundPanel.css";

type Props = {
    gameScenes: GameSceneSkin[];
    gameSceneId: string;
    onGameSceneChange: (id: string) => void;
};

export default function BackgroundPanel({
    gameScenes,
    gameSceneId,
    onGameSceneChange,
}: Props) {
    return (
        <section className="background-panel">
            <header>
                <h3>Board Scene</h3>
            </header>
            <div className="background-grid">
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

                {/* Classic — no scene image, restores the default CSS board */}
                <button
                    type="button"
                    className={`background-option${!gameSceneId ? " is-active" : ""}`}
                    onClick={() => onGameSceneChange("")}
                >
                    <span className="scene-classic-swatch" />
                    <small>Classic</small>
                </button>
            </div>
        </section>
    );
}
