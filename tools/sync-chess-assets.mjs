// Copies shared/chess-assets/ to both runtime asset targets.
// Source of truth: shared/chess-assets/
// Cross-platform replacement for sync-chess-assets.ps1 (Node has no powershell.exe dependency).

import { existsSync, rmSync, mkdirSync, cpSync, readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const source = join(repoRoot, "shared", "chess-assets");
const webTarget = join(repoRoot, "apps", "web-ui", "public", "assets", "chess");
const desktopTarget = join(repoRoot, "apps", "desktop-gui", "modules", "gui", "src", "main", "resources", "assets");

console.log("");
console.log("=== sync-chess-assets ===");
console.log(`Source : ${source}`);
console.log(`Web    : ${webTarget}`);
console.log(`Desktop: ${desktopTarget}`);
console.log("");

if (!existsSync(source)) {
  console.error(`Source not found: ${source}`);
  process.exit(1);
}
if (!existsSync(join(source, "sprite_catalog.json"))) {
  console.error("Missing sprite_catalog.json in source");
  process.exit(1);
}
if (!existsSync(join(source, "images"))) {
  console.error("Missing images/ folder in source");
  process.exit(1);
}

function countFiles(path) {
  let count = 0;
  for (const entry of readdirSync(path, { withFileTypes: true })) {
    const full = join(path, entry.name);
    if (entry.isDirectory()) count += countFiles(full);
    else if (statSync(full).isFile()) count += 1;
  }
  return count;
}

function syncTarget(target) {
  rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });
  cpSync(source, target, { recursive: true });
}

syncTarget(desktopTarget);
console.log(`Desktop: ${countFiles(desktopTarget)} files synced`);

syncTarget(webTarget);

const catalogPath = join(webTarget, "sprite_catalog.json");
const catalog = readFileSync(catalogPath, "utf8").replaceAll('"assets/images/', '"assets/chess/images/');
writeFileSync(catalogPath, catalog, "utf8");

console.log(`Web UI : ${countFiles(webTarget)} files synced (catalog paths rewritten)`);
console.log("");
console.log("Done.");
