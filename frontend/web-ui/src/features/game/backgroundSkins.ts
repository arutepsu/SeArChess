import bg1Url from "../../assets/backgrounds/background1.jpg";
import bg2Url from "../../assets/backgrounds/background2.jpg";
import bg3Url from "../../assets/backgrounds/background3.jpg";

export interface GameBackground {
  id: string;
  label: string;
  imageUrl: string;
}

export const GAME_BACKGROUNDS: GameBackground[] = [
  { id: "bg1", label: "Background 1", imageUrl: bg1Url },
  { id: "bg2", label: "Background 2", imageUrl: bg2Url },
  { id: "bg3", label: "Background 3", imageUrl: bg3Url },
];

export function getBackgroundById(id: string): GameBackground | null {
  return GAME_BACKGROUNDS.find((b) => b.id === id) ?? null;
}
