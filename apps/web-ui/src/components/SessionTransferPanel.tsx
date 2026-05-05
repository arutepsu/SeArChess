import { useCallback, useRef, useState } from "react";
import type { SessionExportEnvelope } from "../api/backendTypes";
import { exportSession } from "../api/client";
import "./SessionTransferPanel.css";

type Props = {
  busy: boolean;
  sessionId: string | null;
  onImportSession: (envelope: SessionExportEnvelope) => Promise<void>;
};

export default function SessionTransferPanel({
  busy,
  sessionId,
  onImportSession
}: Props) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [working, setWorking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleExport = useCallback(async () => {
    if (!sessionId) return;

    setWorking(true);
    setMessage(null);
    setError(null);
    try {
      const envelope = await exportSession(sessionId);
      const blob = new Blob([JSON.stringify(envelope, null, 2)], {
        type: "application/json"
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `searchess-session-${sessionId.slice(0, 8)}.json`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setMessage("Session exported.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Export failed.");
    } finally {
      setWorking(false);
    }
  }, [sessionId]);

  const handleImportFile = useCallback(
    async (file: File | undefined) => {
      if (!file) return;

      setWorking(true);
      setMessage(null);
      setError(null);
      try {
        const text = await file.text();
        const envelope = JSON.parse(text) as SessionExportEnvelope;
        await onImportSession(envelope);
        setMessage("Session imported.");
      } catch (err) {
        setError(err instanceof Error ? err.message : "Import failed.");
      } finally {
        setWorking(false);
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
    },
    [onImportSession]
  );

  return (
    <section className="panel session-transfer-panel" aria-label="Session Transfer">
      <header>
        <h2>Session File</h2>
        <p>Export or import a complete session snapshot.</p>
      </header>
      <div className="session-transfer-actions">
        <button
          type="button"
          disabled={busy || working || !sessionId}
          onClick={() => void handleExport()}
        >
          Export Session
        </button>
        <label className={`session-import${busy || working ? " is-disabled" : ""}`}>
          <span>Import Session</span>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,application/json"
            disabled={busy || working}
            onChange={(event) => void handleImportFile(event.target.files?.[0])}
          />
        </label>
      </div>
      {message && <p className="session-transfer-message">{message}</p>}
      {error && <p className="session-transfer-error">{error}</p>}
    </section>
  );
}
