# Copies shared/chess-assets/ to both runtime asset targets.
# Source of truth: shared/chess-assets/

$RepoRoot = Resolve-Path "$PSScriptRoot\.."
$Source = Join-Path $RepoRoot "shared\chess-assets"
$WebTarget = Join-Path $RepoRoot "apps\web-ui\public\assets\chess"
$DesktopTarget = Join-Path $RepoRoot "apps\desktop-gui\modules\gui\src\main\resources\assets"

Write-Host ""
Write-Host "=== sync-chess-assets ==="
Write-Host "Source : $Source"
Write-Host "Web    : $WebTarget"
Write-Host "Desktop: $DesktopTarget"
Write-Host ""

if (-not (Test-Path $Source)) {
    Write-Error "Source not found: $Source"
    exit 1
}
if (-not (Test-Path "$Source\sprite_catalog.json")) {
    Write-Error "Missing sprite_catalog.json in source"
    exit 1
}
if (-not (Test-Path "$Source\images")) {
    Write-Error "Missing images/ folder in source"
    exit 1
}

function Count-Files($path) {
    (Get-ChildItem $path -Recurse -File).Count
}

Remove-Item $DesktopTarget -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $DesktopTarget | Out-Null
Copy-Item "$Source\*" $DesktopTarget -Recurse -Force
$desktopCount = Count-Files $DesktopTarget
Write-Host "Desktop: $desktopCount files synced"

Remove-Item $WebTarget -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $WebTarget | Out-Null
Copy-Item "$Source\*" $WebTarget -Recurse -Force

$catalogPath = Join-Path $WebTarget "sprite_catalog.json"
$catalog = Get-Content $catalogPath -Raw -Encoding UTF8
$catalog = $catalog -replace '"assets/images/', '"assets/chess/images/'
[System.IO.File]::WriteAllText($catalogPath, $catalog, [System.Text.Encoding]::UTF8)

$webCount = Count-Files $WebTarget
Write-Host "Web UI : $webCount files synced (catalog paths rewritten)"

Write-Host ""
Write-Host "Done."
