param(
  [string]$EdgeBaseUrl = "http://127.0.0.1:10000",
  [switch]$StartStack,
  [switch]$SkipFailurePath,
  [switch]$SkipRejectionPaths
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "==> $Message"
}

function Invoke-JsonRequest {
  param(
    [string]$Method,
    [string]$Url,
    [object]$Body = $null
  )

  $headers = @{ "Content-Type" = "application/json" }
  $jsonBody = $null
  if ($null -ne $Body) {
    $jsonBody = $Body | ConvertTo-Json -Depth 16 -Compress
  }

  try {
    if ($null -ne $jsonBody) {
      $response = Invoke-WebRequest -Method $Method -Uri $Url -Headers $headers -Body $jsonBody -UseBasicParsing
    } else {
      $response = Invoke-WebRequest -Method $Method -Uri $Url -Headers $headers -UseBasicParsing
    }
    $content = $response.Content
    $parsed = if ([string]::IsNullOrWhiteSpace($content)) { $null } else { $content | ConvertFrom-Json }
    return [pscustomobject]@{
      StatusCode = [int]$response.StatusCode
      Body       = $parsed
      RawBody    = $content
    }
  } catch [System.Net.WebException] {
    $httpResponse = $_.Exception.Response
    if ($null -eq $httpResponse) {
      throw
    }
    $reader = New-Object System.IO.StreamReader($httpResponse.GetResponseStream())
    try {
      $content = $reader.ReadToEnd()
    } finally {
      $reader.Dispose()
    }
    $parsed = if ([string]::IsNullOrWhiteSpace($content)) { $null } else { $content | ConvertFrom-Json }
    return [pscustomobject]@{
      StatusCode = [int]$httpResponse.StatusCode
      Body       = $parsed
      RawBody    = $content
    }
  }
}

function Assert-True {
  param(
    [bool]$Condition,
    [string]$Message
  )
  if (-not $Condition) {
    throw $Message
  }
}

function Assert-Status {
  param(
    [object]$Response,
    [int]$Expected,
    [string]$Context
  )
  if ($Response.StatusCode -ne $Expected) {
    throw "$Context expected HTTP $Expected but got HTTP $($Response.StatusCode): $($Response.RawBody)"
  }
}

function New-HumanVsAiGame {
  $create = Invoke-JsonRequest -Method "POST" -Url "$EdgeBaseUrl/api/sessions" -Body @{ mode = "HumanVsAI" }
  Assert-Status $create 201 "Create HumanVsAI session"
  return $create.Body.session.gameId
}

function Submit-OpeningHumanMove([string]$GameId) {
  $move = Invoke-JsonRequest -Method "POST" -Url "$EdgeBaseUrl/api/games/$GameId/moves" -Body @{
    from       = "e2"
    to         = "e4"
    controller = "HumanLocal"
  }
  Assert-Status $move 200 "Submit opening human move"
  return $move
}

function Assert-RemoteAiConfig {
  Write-Step "Checking Game container AI runtime config"
  $mode = docker compose exec -T game-service printenv AI_PROVIDER_MODE
  if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect Game Service AI_PROVIDER_MODE via docker compose exec"
  }
  $baseUrl = docker compose exec -T game-service printenv AI_REMOTE_BASE_URL
  if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect Game Service AI_REMOTE_BASE_URL via docker compose exec"
  }
  Assert-True ($mode.Trim() -eq "remote") "Game Service is not using remote AI mode; AI_PROVIDER_MODE=$mode"
  Assert-True ($baseUrl.Trim() -eq "http://ai-service:8765") "Game Service is not pointing at the internal AI service; AI_REMOTE_BASE_URL=$baseUrl"
  Write-Host "Game Service AI config: AI_PROVIDER_MODE=$($mode.Trim()), AI_REMOTE_BASE_URL=$($baseUrl.Trim())"
}

function Assert-RemoteAiTestMode {
  param([string]$Expected)

  $mode = docker compose exec -T game-service printenv AI_REMOTE_TEST_MODE
  if ($LASTEXITCODE -ne 0) {
    $mode = ""
  }
  $actual = $mode.Trim()
  Assert-True ($actual -eq $Expected) "Expected AI_REMOTE_TEST_MODE='$Expected', got '$actual'"
}

function Wait-ForEdge {
  Write-Step "Waiting for Envoy edge health"
  $deadline = (Get-Date).AddSeconds(60)
  do {
    try {
      $health = Invoke-JsonRequest -Method "GET" -Url "$EdgeBaseUrl/health"
      if ($health.StatusCode -eq 200) {
        Write-Host "Envoy edge is healthy at $EdgeBaseUrl"
        return
      }
    } catch {
      Start-Sleep -Seconds 1
    }
    Start-Sleep -Seconds 1
  } while ((Get-Date) -lt $deadline)

  throw "Envoy edge did not become healthy at $EdgeBaseUrl"
}

function Wait-ForInternalAi {
  Write-Step "Waiting for AI service on the internal Compose network"
  $deadline = (Get-Date).AddSeconds(60)
  do {
    $previousErrorActionPreference = $ErrorActionPreference
    $exitCode = 1
    try {
      $ErrorActionPreference = "Continue"
      docker compose exec -T game-service curl -fsS http://ai-service:8765/health *> $null
      $exitCode = $LASTEXITCODE
    } catch {
      $exitCode = 1
    } finally {
      $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -eq 0) {
      Write-Host "AI service is reachable from Game Service at http://ai-service:8765"
      return
    }
    Start-Sleep -Seconds 1
  } while ((Get-Date) -lt $deadline)

  throw "AI service did not become reachable from Game Service at http://ai-service:8765"
}

function Restart-GameServiceWithRemoteTestMode {
  param([string]$Mode)

  if ([string]::IsNullOrWhiteSpace($Mode)) {
    Write-Step "Restoring normal Game Service remote AI configuration"
    Remove-Item Env:\AI_REMOTE_TEST_MODE -ErrorAction SilentlyContinue
  } else {
    Write-Step "Recreating Game Service with AI_REMOTE_TEST_MODE=$Mode"
    $env:AI_REMOTE_TEST_MODE = $Mode
  }

  docker compose up -d --force-recreate game-service | Write-Host
  Wait-ForEdge
  Assert-RemoteAiConfig
  Assert-RemoteAiTestMode $Mode
  Wait-ForInternalAi
}

function New-GameReadyForBlackAiTurn {
  $gameId = New-HumanVsAiGame
  Submit-OpeningHumanMove $gameId | Out-Null
  $before = Invoke-JsonRequest -Method "GET" -Url "$EdgeBaseUrl/api/games/$gameId"
  Assert-Status $before 200 "Fetch game before AI rejection"
  Assert-True ($before.Body.moveHistory.Count -eq 1) "Expected one human move before AI rejection, got $($before.Body.moveHistory.Count)"
  Assert-True ($before.Body.currentPlayer -eq "Black") "Expected Black AI turn before rejection, got $($before.Body.currentPlayer)"
  return [pscustomobject]@{ GameId = $gameId; Before = $before }
}

function Assert-GameUnchanged {
  param(
    [string]$GameId,
    [object]$Before,
    [string]$Context
  )

  $after = Invoke-JsonRequest -Method "GET" -Url "$EdgeBaseUrl/api/games/$GameId"
  Assert-Status $after 200 "$Context fetch game after rejection"

  $beforeJson = $Before.Body | ConvertTo-Json -Depth 32 -Compress
  $afterJson = $after.Body | ConvertTo-Json -Depth 32 -Compress
  Assert-True ($afterJson -eq $beforeJson) "$Context mutated persisted game state unexpectedly"
}

function Verify-AiRejectionPath {
  param(
    [string]$Mode,
    [string]$Label
  )

  Restart-GameServiceWithRemoteTestMode $Mode
  $case = New-GameReadyForBlackAiTurn

  $response = Invoke-JsonRequest -Method "POST" -Url "$EdgeBaseUrl/api/games/$($case.GameId)/ai-move"
  Assert-Status $response 422 "$Label AI rejection"
  Assert-True ($response.Body.code -eq "AI_MOVE_REJECTED") "$Label expected AI_MOVE_REJECTED, got $($response.Body.code)"
  Assert-GameUnchanged -GameId $case.GameId -Before $case.Before -Context $Label
  Write-Host "$Label rejected with $($response.StatusCode) $($response.Body.code); game state remained unchanged."
}

if ($StartStack) {
  Write-Step "Starting Docker Compose stack"
  docker compose up -d | Write-Host
}

Wait-ForEdge
Assert-RemoteAiConfig
Wait-ForInternalAi

Write-Step "Verifying successful remote AI move flow through Envoy"
$gameId = New-HumanVsAiGame
Submit-OpeningHumanMove $gameId | Out-Null

$aiMove = Invoke-JsonRequest -Method "POST" -Url "$EdgeBaseUrl/api/games/$gameId/ai-move"
Assert-Status $aiMove 200 "Trigger remote AI move"
Assert-True ($aiMove.Body.game.moveHistory.Count -eq 2) "Expected two moves after remote AI response, got $($aiMove.Body.game.moveHistory.Count)"
Assert-True ($aiMove.Body.game.currentPlayer -eq "White") "Expected turn to return to White after Black AI move, got $($aiMove.Body.game.currentPlayer)"
Assert-True ($null -ne $aiMove.Body.game.lastMove) "Expected lastMove to be populated after AI move"

$stored = Invoke-JsonRequest -Method "GET" -Url "$EdgeBaseUrl/api/games/$gameId"
Assert-Status $stored 200 "Fetch game after remote AI move"
Assert-True ($stored.Body.moveHistory.Count -eq 2) "Persisted game did not retain both moves"
Write-Host "Remote AI move applied for game $gameId; move history length is $($stored.Body.moveHistory.Count)."

if (-not $SkipFailurePath) {
  Write-Step "Verifying AI unavailable failure path"
  $aiWasStopped = $false
  try {
    docker compose stop ai-service | Write-Host
    $aiWasStopped = $true

    $failureGameId = New-HumanVsAiGame
    Submit-OpeningHumanMove $failureGameId | Out-Null
    $failure = Invoke-JsonRequest -Method "POST" -Url "$EdgeBaseUrl/api/games/$failureGameId/ai-move"
    Assert-Status $failure 503 "Trigger AI move while AI service is stopped"
    Assert-True ($failure.Body.code -eq "AI_PROVIDER_FAILED") "Expected AI_PROVIDER_FAILED, got $($failure.Body.code)"
    Write-Host "AI unavailable path returned $($failure.StatusCode) $($failure.Body.code) as expected."
  } finally {
    if ($aiWasStopped) {
      Write-Step "Restarting AI service"
      docker compose up -d ai-service | Write-Host
      Wait-ForInternalAi
    }
  }
}

if (-not $SkipRejectionPaths) {
  try {
    Write-Step "Verifying bad remote AI output is rejected without state mutation"
    Verify-AiRejectionPath -Mode "illegal_move" -Label "Illegal remote AI move"
    Verify-AiRejectionPath -Mode "malformed_response" -Label "Malformed remote AI response"
  } finally {
    Restart-GameServiceWithRemoteTestMode ""
  }
}

Write-Step "Remote AI flow verification completed"
