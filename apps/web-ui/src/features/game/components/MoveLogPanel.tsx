import { useEffect } from "react";
import type { MoveRecord, PieceCode } from "../../../api/types";
import type { SpriteCatalog } from "../../../assets/spriteCatalog";
import Button from "../../../components/ui/Button";
import CapturedPanel from "./CapturedPanel";
import MoveList from "./MoveList";
import "./MoveLogPanel.css";

interface MoveLogPanelProps {
  isOpen: boolean;
  onClose: () => void;
  moves: MoveRecord[];
  captured?: PieceCode[];
  spriteCatalog: SpriteCatalog | null;
}

export default function MoveLogPanel({
  isOpen,
  onClose,
  moves,
  captured,
  spriteCatalog,
}: MoveLogPanelProps) {
  useEffect(() => {
    if (!isOpen) return;

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <>
      <div className="move-log-panel__backdrop" onClick={onClose} aria-hidden="true" />

      <aside
        className="move-log-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="move-log-panel-title"
      >
        <header className="move-log-panel__header">
          <h2 id="move-log-panel-title">Moves</h2>
          <Button variant="ghost" onClick={onClose} aria-label="Close move log">
            Close
          </Button>
        </header>

        <div className="move-log-panel__body">
          <MoveList moves={moves} />
          <CapturedPanel captured={captured} spriteCatalog={spriteCatalog} />
        </div>
      </aside>
    </>
  );
}
