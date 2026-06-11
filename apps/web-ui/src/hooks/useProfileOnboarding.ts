import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getMyProfile } from "../api/userServiceClient";
import type { UserProfileResponse } from "../api/userServiceTypes";

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
