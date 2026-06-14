import { useEffect, useState } from "react";

const backgrounds = [
  { id: "river", label: "River", url: "/assets/backgrounds/river.png" },
  { id: "sakura-grove", label: "Grove", url: "/assets/backgrounds/sakuratrees.jpg" },
  { id: "forest", label: "Forest", url: "/assets/backgrounds/new.jpg" },
];

export function useBackgroundSelection(suppressed = false) {
  const [backgroundId, setBackgroundId] = useState(backgrounds[0].id);

  useEffect(() => {
    if (suppressed) {
      document.documentElement.style.setProperty("--app-background", "none");
      return;
    }
    const match = backgrounds.find((item) => item.id === backgroundId);
    const nextUrl = match?.url ?? backgrounds[0].url;
    document.documentElement.style.setProperty(
      "--app-background",
      `url("${nextUrl}")`
    );
  }, [backgroundId, suppressed]);

  return { backgrounds, backgroundId, setBackgroundId };
}
