import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getMyProfile } from "../api/userServiceClient";
import type { UserProfileResponse } from "../api/userServiceTypes";
import keycloak, { authEnabled } from "../auth/keycloak";

export interface UseProfileOnboardingResult {
  profile: UserProfileResponse | null;
  onboardingRequired: boolean;
  setOnboardingRequired: (v: boolean) => void;
}

export function useProfileOnboarding(): UseProfileOnboardingResult {
  const [profile, setProfile] = useState<UserProfileResponse | null>(null);
  const [onboardingRequired, setOnboardingRequired] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    if (authEnabled && !keycloak.authenticated) return;

    getMyProfile()
      .then((p) => {
        setProfile(p);
        setOnboardingRequired(p.onboardingRequired);
        if (p.onboardingRequired) navigate("/onboarding");
      })
      .catch(() => { /* don't block the app if user-service is unreachable */ });
  }, [navigate]);

  return { profile, onboardingRequired, setOnboardingRequired };
}
