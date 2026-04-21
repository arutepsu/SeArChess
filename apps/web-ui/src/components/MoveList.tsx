import type { MoveRecord } from "../api/types";
import "./MoveList.css";

type MoveListProps = {
  moves: MoveRecord[];
};

export default function MoveList({ moves }: MoveListProps) {
  return (
    <section className="move-list-panel" aria-label="Move list">
      <header>
        <h2>Move Log</h2>
        <p>Every step, every pivot.</p>
      </header>
      <div className="move-list">
        {moves.length === 0 ? (
          <div className="empty">No moves yet.</div>
        ) : (
          moves.map((move) => (
            <div key={move.ply} className="move">
              <span className="ply">#{move.ply}</span>
              <span className="notation">{move.notation}</span>
              <span className="coords">
                {move.from} -&gt; {move.to}
              </span>
            </div>
          ))
        )}
      </div>
    </section>
  );
}
