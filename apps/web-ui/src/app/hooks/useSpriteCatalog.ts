import { useEffect, useState } from "react";
import type { SpriteCatalog } from "../../assets/spriteCatalog";
import { loadSpriteCatalog } from "../../assets/spriteCatalog";

export function useSpriteCatalog(): SpriteCatalog | null {
  const [spriteCatalog, setSpriteCatalog] = useState<SpriteCatalog | null>(null);

  useEffect(() => {
    let active = true;
    loadSpriteCatalog()
      .then((catalog) => {
        if (active) setSpriteCatalog(catalog);
      })
      .catch(() => {
        if (active) setSpriteCatalog(null);
      });
    return () => {
      active = false;
    };
  }, []);

  return spriteCatalog;
}
