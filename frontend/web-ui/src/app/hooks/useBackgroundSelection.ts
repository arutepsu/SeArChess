import { useState, useEffect } from "react";
import { GAME_BACKGROUNDS, getBackgroundById } from "../../features/game/backgroundSkins";
import type { GameBackground } from "../../features/game/backgroundSkins";

export type { GameBackground };

export function useBackgroundSelection() {
  const [backgroundId, setBackgroundId] = useState("bg1");
  const background = getBackgroundById(backgroundId);

  useEffect(() => {
    const value = background ? `url("${background.imageUrl}")` : "none";
    document.documentElement.style.setProperty("--app-background", value);
    return () => {
      document.documentElement.style.removeProperty("--app-background");
    };
  }, [background]);

  return { backgroundId, setBackgroundId, background, backgrounds: GAME_BACKGROUNDS };
}
