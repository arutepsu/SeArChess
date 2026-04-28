import { useState } from "react";
import type { MigrationBackend, MigrationMode, MigrationReport } from "../api/migrationTypes";
import { runMigration } from "../api/client";
import "./PersistenceAdminPage.css";

interface Props {
  onBack: () => void;
}

interface FormState {
  source: MigrationBackend;
  target: MigrationBackend;
  mode: MigrationMode;
  batchSize: number;
  validateAfterExecute: boolean;
  confirmation: string;
  adminToken: string;
}

const defaultForm: FormState = {
  source: "postgres",
  target: "mongo",
  mode: "dry-run",
  batchSize: 100,
  validateAfterExecute: false,
  confirmation: "",
  adminToken: ""
};

export default function PersistenceAdminPage({ onBack }: Props) {
  const [form, setForm] = useState<FormState>(defaultForm);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<MigrationReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const sameBackend = form.source === form.target;
  const needsConfirmation = form.mode === "execute";
  const confirmationOk = !needsConfirmation || form.confirmation === "MIGRATE";
  const confirmationTyped = needsConfirmation && form.confirmation.length > 0;
  const confirmationWrong = confirmationTyped && form.confirmation !== "MIGRATE";
  const canSubmit = !loading && !sameBackend && confirmationOk;

  function setField<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function handleModeChange(mode: MigrationMode) {
    setForm((prev) => ({
      ...prev,
      mode,
      validateAfterExecute: mode === "execute" ? prev.validateAfterExecute : false,
      confirmation: mode === "execute" ? prev.confirmation : ""
    }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;

    setLoading(true);
    setResult(null);
    setError(null);

    try {
      const report = await runMigration(
        {
          source: form.source,
          target: form.target,
          mode: form.mode,
          batchSize: form.batchSize,
          validateAfterExecute: form.mode === "execute" ? form.validateAfterExecute : false,
          ...(form.mode === "execute" ? { confirmation: form.confirmation } : {})
        },
        form.adminToken || undefined
      );
      setResult(report);
    } catch (err) {
      if (err instanceof Error && err.message.startsWith("UNAUTHORIZED:")) {
        setError("Admin token missing or invalid.");
      } else {
        setError(err instanceof Error ? err.message : "Unknown error");
      }
    } finally {
      setLoading(false);
    }
  }

  const submitLabel = loading
    ? "Running…"
    : form.mode === "execute"
      ? "Execute Migration"
      : form.mode === "validate-only"
        ? "Run Validation"
        : "Run Dry Run";

  return (
    <div className="admin-page">
      <header className="admin-page-header">
        <button type="button" className="admin-back-btn" onClick={onBack}>
          ← Back
        </button>
        <div className="admin-title-block">
          <span className="admin-internal-badge">Internal Tool</span>
          <h1>Persistence Admin</h1>
          <p className="admin-subtitle">
            Internal migration and validation tool. Not for player use. Requires{" "}
            <code>MIGRATION_ADMIN_ENABLED=true</code>.
          </p>
        </div>
      </header>

      <section className="panel admin-form-panel">
        <h2 className="admin-section-label">Migration</h2>

        <form className="admin-form" onSubmit={(e) => { void handleSubmit(e); }}>
          {/* Source / Target */}
          <div className="admin-field-row">
            <div className="admin-field">
              <label htmlFor="admin-source">Source</label>
              <select
                id="admin-source"
                value={form.source}
                onChange={(e) => setField("source", e.target.value as MigrationBackend)}
              >
                <option value="postgres">postgres</option>
                <option value="mongo">mongo</option>
              </select>
            </div>
            <div className="admin-field">
              <label htmlFor="admin-target">Target</label>
              <select
                id="admin-target"
                value={form.target}
                onChange={(e) => setField("target", e.target.value as MigrationBackend)}
              >
                <option value="postgres">postgres</option>
                <option value="mongo">mongo</option>
              </select>
            </div>
          </div>

          {sameBackend && (
            <p className="admin-field-error" role="alert">
              Source and target must differ.
            </p>
          )}

          {/* Admin Token */}
          <div className="admin-field">
            <label htmlFor="admin-token">Admin Token</label>
            <p className="admin-field-hint">
              Shared secret configured via <code>MIGRATION_ADMIN_TOKEN</code> on the server.
            </p>
            <input
              id="admin-token"
              type="password"
              placeholder="Enter admin token"
              autoComplete="off"
              value={form.adminToken}
              onChange={(e) => setField("adminToken", e.target.value)}
            />
          </div>

          {/* Mode */}
          <div className="admin-field">
            <span className="admin-field-label">Mode</span>
            <div className="admin-radio-group" role="radiogroup" aria-label="Migration mode">
              {(
                [
                  {
                    value: "dry-run" as MigrationMode,
                    label: "DryRun",
                    description: "Reads only — shows what would be migrated, no writes"
                  },
                  {
                    value: "validate-only" as MigrationMode,
                    label: "ValidateOnly",
                    description: "Compares source and target without writing"
                  },
                  {
                    value: "execute" as MigrationMode,
                    label: "Execute ⚠",
                    description: "Writes data to target — destructive if target has conflicting data",
                    isWrite: true
                  }
                ] as const
              ).map(({ value, label, description, isWrite }) => {
                const active = form.mode === value;
                return (
                  <label
                    key={value}
                    className={`admin-radio-label${active ? " is-active" : ""}${isWrite ? " is-write" : ""}`}
                  >
                    <input
                      type="radio"
                      name="mode"
                      value={value}
                      checked={active}
                      onChange={() => handleModeChange(value)}
                    />
                    <span className="admin-radio-text">
                      <span>{label}</span>
                      <small>{description}</small>
                    </span>
                  </label>
                );
              })}
            </div>
          </div>

          {/* Batch size + validateAfterExecute */}
          <div className="admin-field-row">
            <div className="admin-field">
              <label htmlFor="admin-batch-size">Batch Size</label>
              <input
                id="admin-batch-size"
                type="number"
                min={1}
                value={form.batchSize}
                onChange={(e) => setField("batchSize", Math.max(1, parseInt(e.target.value, 10) || 1))}
              />
            </div>
            <div className="admin-field admin-field-checkbox">
              <span className="admin-field-label">Options</span>
              <label className={`admin-checkbox-label${form.mode !== "execute" ? " is-disabled" : ""}`}>
                <input
                  type="checkbox"
                  checked={form.validateAfterExecute}
                  disabled={form.mode !== "execute"}
                  onChange={(e) => setField("validateAfterExecute", e.target.checked)}
                />
                <span>Validate after execute</span>
              </label>
            </div>
          </div>

          {/* Confirmation — execute only */}
          {form.mode === "execute" && (
            <div className="admin-field">
              <label htmlFor="admin-confirmation">Confirmation</label>
              <p className="admin-field-hint">
                Type <strong>MIGRATE</strong> to enable the execute button.
              </p>
              <input
                id="admin-confirmation"
                type="text"
                placeholder="MIGRATE"
                autoComplete="off"
                value={form.confirmation}
                className={confirmationWrong ? "is-error" : ""}
                onChange={(e) => setField("confirmation", e.target.value)}
              />
            </div>
          )}

          <button
            type="submit"
            className={`admin-submit-btn${form.mode === "execute" ? " is-write" : ""}`}
            disabled={!canSubmit}
          >
            {submitLabel}
          </button>
        </form>
      </section>

      {error && (
        <div className="admin-error-panel" role="alert">
          <h3>Error</h3>
          <p>{error}</p>
        </div>
      )}

      {result && <ReportPanel report={result} />}
    </div>
  );
}

function ReportPanel({ report }: { report: MigrationReport }) {
  const statusKey = report.status.toLowerCase().replace(/\s+/g, "");

  return (
    <section className="panel admin-result-panel">
      <div className="result-status-row">
        <span className={`result-status-badge status-${statusKey}`}>{report.status}</span>
        <span className="result-h2">Migration Report</span>
      </div>

      <div className="result-meta">
        <span>
          <span className="result-meta-label">Run&nbsp;</span>
          {report.runId}
        </span>
        <span>
          <span className="result-meta-label">Route&nbsp;</span>
          {report.source} → {report.target}
        </span>
        <span>
          <span className="result-meta-label">Mode&nbsp;</span>
          {report.mode}
        </span>
        <span>
          <span className="result-meta-label">Started&nbsp;</span>
          {report.startedAt}
        </span>
        <span>
          <span className="result-meta-label">Duration&nbsp;</span>
          {report.duration}
        </span>
        <span>
          <span className="result-meta-label">Batches&nbsp;</span>
          {report.batchCount} × {report.batchSize}
        </span>
      </div>

      <div className="result-stats-grid">
        <StatCard label="Scanned" value={report.scanned} />
        <StatCard label="Migrated" value={report.migrated} goodWhenNonZero />
        <StatCard label="Would Migrate" value={report.wouldMigrate} goodWhenNonZero />
        <StatCard label="Skipped Equiv." value={report.skippedEquivalent} />
        <StatCard label="Conflicts" value={report.conflicts} warnWhenNonZero />
        <StatCard label="Failed" value={report.failed} errorWhenNonZero />
        <StatCard label="Write Failures" value={report.writeFailures} errorWhenNonZero />
        <StatCard label="Read Failures" value={report.readFailures} errorWhenNonZero />
        <StatCard label="Src Data Missing" value={report.sourceDataMissing} warnWhenNonZero />
      </div>

      {report.validationRan && (
        <div className="result-validation-block">
          <span className="admin-section-label">Validation</span>
          <div className="result-validation-row">
            <span className="result-meta-label">Result</span>
            <span
              className={
                report.validationResult === "Passed"
                  ? "validation-passed"
                  : report.validationResult === "Failed"
                    ? "validation-failed"
                    : ""
              }
            >
              {report.validationResult ?? "—"}
            </span>
          </div>
          <div className="result-validation-row">
            <span className="result-meta-label">Validated Equiv.</span>
            <span>{report.validatedEquivalent}</span>
          </div>
          <div className="result-validation-row">
            <span className="result-meta-label">Mismatches</span>
            <span className={report.validationMismatches > 0 ? "validation-failed" : ""}>
              {report.validationMismatches}
            </span>
          </div>
        </div>
      )}

      {report.fatalFailure && (
        <div className="result-fatal-block" role="alert">
          <strong>Fatal failure:</strong> {report.fatalFailure}
        </div>
      )}

      {report.itemResultsPreview.length > 0 && (
        <details className="result-details">
          <summary>Item results preview ({report.itemResultsPreview.length})</summary>
          <div className="result-details-body">
            {report.itemResultsPreview.map((item, i) => (
              <div key={i} className="item-row">
                <span className="item-type">{item.type}</span>
                <span className="item-id" title={item.sessionId}>
                  {item.sessionId.slice(0, 8)}…
                </span>
                <span className="item-id" title={item.gameId}>
                  game:{item.gameId.slice(0, 8)}…
                </span>
                {(item.reason ?? item.message) && (
                  <span className="item-detail">{item.reason ?? item.message}</span>
                )}
              </div>
            ))}
          </div>
        </details>
      )}

      <details className="result-details">
        <summary>Raw JSON</summary>
        <div className="result-details-body">
          <pre>{JSON.stringify(report, null, 2)}</pre>
        </div>
      </details>
    </section>
  );
}

interface StatCardProps {
  label: string;
  value: number;
  goodWhenNonZero?: boolean;
  warnWhenNonZero?: boolean;
  errorWhenNonZero?: boolean;
}

function StatCard({ label, value, goodWhenNonZero, warnWhenNonZero, errorWhenNonZero }: StatCardProps) {
  const valueClass =
    value > 0 && goodWhenNonZero
      ? "stat-card-value is-positive-good"
      : value > 0 && warnWhenNonZero
        ? "stat-card-value is-nonzero-warn"
        : value > 0 && errorWhenNonZero
          ? "stat-card-value is-nonzero-error"
          : "stat-card-value";

  return (
    <div className="stat-card">
      <span className="stat-card-label">{label}</span>
      <span className={valueClass}>{value}</span>
    </div>
  );
}
