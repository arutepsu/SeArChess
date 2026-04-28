import type { PieceCode } from "../api/types";
import type { SpriteCatalog } from "../assets/spriteCatalog";

type Props = {
    captured?: PieceCode[];
    spriteCatalog: SpriteCatalog | null;
};

function spriteInfoFor(
    piece: PieceCode,
    spriteCatalog: SpriteCatalog | null
): { url: string; frameCount: number } | null {
    if (!spriteCatalog) return null;

    const color = piece.startsWith("w") ? "white" : "black";
    const letter = piece[1];

    const nameMap: Record<string, string> = {
        K: "king",
        Q: "queen",
        R: "rook",
        B: "bishop",
        N: "knight",
        P: "pawn"
    };

    const name = nameMap[letter] ?? "pawn";
    const key = `classic/${color}_${name}_idle`;
    const sheet = spriteCatalog.spriteSheets[key];

    if (!sheet) return null;

    const clipSpec = spriteCatalog.clipSpecs[sheet.clipSpec];

    if (!clipSpec) return null;

    return {
        url: `/${sheet.path}`,
        frameCount: clipSpec.frameCount
    };
}

export default function CapturedPanel({ captured, spriteCatalog }: Props) {
    return (
        <section className="panel capture-panel">
            <header>
                <h2>Captured</h2>
                <p>Pieces claimed during the match.</p>
            </header>

            <div className="captured">
                {!captured || captured.length === 0 ? (
                    <span>None yet.</span>
                ) : (
                    captured.map((piece, index) => {
                        const sprite = spriteInfoFor(piece, spriteCatalog);
                        const frameCount = sprite?.frameCount ?? 1;

                        const style = sprite
                            ? {
                                backgroundImage: `url(${sprite.url})`,
                                backgroundSize: `${frameCount * 100}% 100%`,
                                backgroundPosition: "0% 50%"
                            }
                            : undefined;

                        return (
                            <span
                                key={`${piece}-${index}`}
                                className={`captured-piece${piece.startsWith("b") ? " is-black" : ""
                                    }${sprite ? " has-sprite" : ""}`}
                                style={style}
                                aria-label={piece}
                            >
                                {sprite ? "" : piece}
                            </span>
                        );
                    })
                )}
            </div>
        </section>
    );
}
