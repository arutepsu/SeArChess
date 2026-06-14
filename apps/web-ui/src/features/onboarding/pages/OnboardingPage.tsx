import { useState } from "react";
import { patchProfile } from "../../../api/userServiceClient";
import Button from "../../../components/ui/Button";
import "./OnboardingPage.css";

interface OnboardingPageProps {
  onComplete: () => void;
}

export default function OnboardingPage({ onComplete }: OnboardingPageProps) {
  const [nickname, setNickname] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    const trimmed = nickname.trim();
    if (!trimmed) return;
    setSaving(true);
    setError(null);
    try {
      await patchProfile({ nickname: trimmed });
      onComplete();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Failed to save nickname";
      setError(message.includes("already taken") ? `"${trimmed}" is already taken. Please choose another.` : message);
      setSaving(false);
    }
  };

  return (
    <div className="onboarding-page">
      <div className="onboarding-card">
        <h1>Welcome to SeArChess</h1>
        <p className="onboarding-intro">
          Choose your Searchess nickname to get started. This is how other players will see you.
        </p>
        <div className="onboarding-form">
          <label htmlFor="nickname-input">Searchess nickname</label>
          <input
            id="nickname-input"
            type="text"
            placeholder="e.g. CoolPlayer42"
            value={nickname}
            disabled={saving}
            maxLength={24}
            onChange={(e) => setNickname(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") void handleSubmit();
            }}
            autoFocus
          />
          <p className="onboarding-hint">3–24 characters: letters, digits, underscore (_), hyphen (-)</p>
          {error ? <p className="onboarding-error">{error}</p> : null}
          <Button
            variant="primary"
            className="onboarding-submit"
            disabled={saving || nickname.trim().length < 3}
            onClick={() => void handleSubmit()}
          >
            {saving ? "Saving…" : "Create nickname"}
          </Button>
        </div>
      </div>
    </div>
  );
}
