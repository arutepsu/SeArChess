type Props = {
    backgroundId: string;
};

export default function BackgroundEffectsLayer({ backgroundId }: Props) {
    const isRainBackground = backgroundId === "river";
    const isSakuraBackground = backgroundId === "sakura-grove";

    if (isRainBackground) {
        return (
            <div className="rain-layer" aria-hidden="true">
                <img className="rain-gif" src="/assets/backgrounds/rain.gif" alt="" />
            </div>
        );
    }

    if (isSakuraBackground) {
        return (
            <div className="sakura-layer" aria-hidden="true">
                <img
                    className="sakura-leaf sakura-1"
                    src="/assets/backgrounds/sakuraleaf1.png"
                    alt=""
                />
                <img
                    className="sakura-leaf sakura-2"
                    src="/assets/backgrounds/sakuraleaf.png"
                    alt=""
                />
                <img
                    className="sakura-leaf sakura-3"
                    src="/assets/backgrounds/sakuraleaf1.png"
                    alt=""
                />
                <img
                    className="sakura-leaf sakura-4"
                    src="/assets/backgrounds/sakuraleaf.png"
                    alt=""
                />
                <img
                    className="sakura-leaf sakura-5"
                    src="/assets/backgrounds/sakuraleaf.png"
                    alt=""
                />
            </div>
        );
    }

    return (
        <div className="leaf-layer" aria-hidden="true">
            <span className="leaf leaf-1"></span>
            <span className="leaf leaf-2"></span>
            <span className="leaf leaf-3"></span>
            <span className="leaf leaf-4"></span>
            <span className="leaf leaf-5"></span>
            <span className="leaf leaf-6"></span>
        </div>
    );
}
