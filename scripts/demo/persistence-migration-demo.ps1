param(
  [switch]$NoReset,
  [switch]$SkipBuild,
  [switch]$LeaveMongo,
  [switch]$ReturnToPostgres
)

$ErrorActionPreference = "Stop"

if ($LeaveMongo -and $ReturnToPostgres) {
  throw "Use either -LeaveMongo or -ReturnToPostgres, not both."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

$apiBaseUrl = "http://127.0.0.1:10000/api"
$edgeHealthUrl = "http://127.0.0.1:10000/health"
$createdGameId = $null
$previousPersistenceMode = $env:PERSISTENCE_MODE

function Write-Step([string]$Message) {
  Write-Host ""
  Write-Host "================================================================================"
  Write-Host $Message
  Write-Host "================================================================================"
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

function Invoke-CommandChecked {
  param(
    [string]$File,
    [string[]]$Arguments,
    [switch]$Quiet
  )

  $commandLine = "$File $($Arguments -join ' ')"
  if (-not $Quiet) {
    Write-Host "> $commandLine"
  }

  $resolvedFile = (Get-Command $File -ErrorAction Stop).Source
  $argumentString = (($Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join " ")
  $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
  if ([System.IO.Path]::GetExtension($resolvedFile) -in @(".bat", ".cmd")) {
    $processInfo.FileName = $env:ComSpec
    $batchCommand = (ConvertTo-ProcessArgument $resolvedFile)
    if (-not [string]::IsNullOrWhiteSpace($argumentString)) {
      $batchCommand = "$batchCommand $argumentString"
    }
    $processInfo.Arguments = '/d /s /c "' + $batchCommand + '"'
  } else {
    $processInfo.FileName = $resolvedFile
    $processInfo.Arguments = $argumentString
  }
  $processInfo.WorkingDirectory = (Get-Location).Path
  $processInfo.UseShellExecute = $false
  $processInfo.RedirectStandardOutput = $true
  $processInfo.RedirectStandardError = $true

  $process = [System.Diagnostics.Process]::Start($processInfo)
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.WaitForExit()

  $exitCode = $process.ExitCode
  $stdout = $stdoutTask.Result
  $stderr = $stderrTask.Result
  $output = @()
  if (-not [string]::IsNullOrWhiteSpace($stdout) -and $stdout.Trim() -eq $stderr.Trim()) {
    $output += $stdout -split "\r?\n"
  } else {
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
      $output += $stderr -split "\r?\n"
    }
    if (-not [string]::IsNullOrWhiteSpace($stdout)) {
      $output += $stdout -split "\r?\n"
    }
  }
  $dedupedOutput = New-Object System.Collections.Generic.List[string]
  $seenOutput = New-Object "System.Collections.Generic.HashSet[string]"
  foreach ($line in $output) {
    $normalisedLine = $line.TrimEnd()
    if ([string]::IsNullOrWhiteSpace($normalisedLine)) {
      continue
    }
    if ($seenOutput.Add($normalisedLine)) {
      [void]$dedupedOutput.Add($normalisedLine)
    }
  }
  $output = $dedupedOutput

  if (-not $Quiet) {
    foreach ($line in $output) {
      Write-Host $line
    }
  }

  if ($exitCode -ne 0) {
    throw "Command failed with exit code ${exitCode}: $commandLine"
  }

  return ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
}

function ConvertTo-ProcessArgument {
  param([string]$Argument)

  if ($null -eq $Argument) {
    return '""'
  }
  if ($Argument -notmatch '[\s"]') {
    return $Argument
  }

  $builder = [System.Text.StringBuilder]::new()
  [void]$builder.Append('"')
  $backslashes = 0
  foreach ($character in $Argument.ToCharArray()) {
    if ($character -eq '\') {
      $backslashes += 1
    } elseif ($character -eq '"') {
      [void]$builder.Append("\" * (($backslashes * 2) + 1))
      [void]$builder.Append('"')
      $backslashes = 0
    } else {
      [void]$builder.Append("\" * $backslashes)
      [void]$builder.Append($character)
      $backslashes = 0
    }
  }
  [void]$builder.Append("\" * ($backslashes * 2))
  [void]$builder.Append('"')
  return $builder.ToString()
}

function Invoke-ApiJson {
  param(
    [string]$Method,
    [string]$Path,
    [object]$Body = $null
  )

  $uri = "$apiBaseUrl$Path"
  $headers = @{ "Content-Type" = "application/json" }

  if ($null -eq $Body) {
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
  }

  $jsonBody = $Body | ConvertTo-Json -Depth 16 -Compress
  return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Body $jsonBody
}

function Get-NestedValue {
  param(
    [object]$Object,
    [string]$Path
  )

  $current = $Object
  foreach ($part in $Path.Split(".")) {
    if ($null -eq $current) {
      return $null
    }

    $property = $current.PSObject.Properties[$part]
    if ($null -eq $property) {
      return $null
    }
    $current = $property.Value
  }
  return $current
}

function Get-FirstNestedValue {
  param(
    [object]$Object,
    [string[]]$Paths
  )

  foreach ($path in $Paths) {
    $value = Get-NestedValue -Object $Object -Path $path
    if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
      return $value
    }
  }
  return $null
}

function Wait-ForComposeService {
  param(
    [string]$Service,
    [int]$TimeoutSeconds = 120
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $containerId = (& docker compose ps -q $Service 2>$null)
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
      $containerId = $containerId.Trim()
      $status = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $containerId 2>$null)
      if ($LASTEXITCODE -eq 0) {
        $status = $status.Trim()
        if ($status -eq "healthy" -or $status -eq "running") {
          Write-Host "$Service is $status."
          return
        }
      }
    }

    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)

  throw "Timed out waiting for $Service to become healthy/running."
}

function Wait-ForStack {
  Write-Step "Waiting for Compose services and Envoy health"

  foreach ($service in @("postgres", "mongo", "ai-service", "history-service", "game-service", "envoy")) {
    Wait-ForComposeService -Service $service
  }

  $deadline = (Get-Date).AddSeconds(90)
  do {
    try {
      $health = Invoke-RestMethod -Method Get -Uri $edgeHealthUrl
      if ($null -ne $health) {
        Write-Host "Envoy edge health is reachable at $edgeHealthUrl."
        return
      }
    } catch {
      Start-Sleep -Seconds 2
    }
  } while ((Get-Date) -lt $deadline)

  throw "Timed out waiting for Envoy edge health at $edgeHealthUrl."
}

function Assert-GameServicePersistenceLog {
  param([string]$Expected)

  $logs = Invoke-CommandChecked -File "docker" -Arguments @("compose", "logs", "--no-color", "--tail=120", "game-service") -Quiet
  $needle = '"persistence":"' + $Expected + '"'
  Assert-True ($logs.Contains($needle)) "Expected game-service logs to contain $needle."
  Write-Host "Game Service logs show persistence=$Expected."
}

function Invoke-Migration {
  param(
    [string]$Label,
    [string[]]$MigrationArguments,
    [switch]$ExpectValidationPassed,
    [switch]$ExpectSkippedEquivalent
  )

  Write-Step $Label
  $sbtCommand = "gameService/runMain chess.server.migration.PersistenceMigrationMain $($MigrationArguments -join ' ')"
  $output = Invoke-CommandChecked -File "sbt" -Arguments @($sbtCommand)

  Assert-True ($output -match "Status:\s+Success") "$Label did not report Status: Success."

  if ($ExpectValidationPassed) {
    Assert-True ($output -match "Validation result:\s+Passed") "$Label did not report Validation result: Passed."
  }

  if ($ExpectSkippedEquivalent) {
    Assert-True (
      $output -match "Skipped equivalent:\s*[1-9][0-9]*" -or $output -match "SkippedEquivalent"
    ) "$Label did not show skipped equivalent data for the idempotency run."
  }

  return $output
}

function Set-MigrationEnvironment {
  $env:SEARCHESS_POSTGRES_URL = "jdbc:postgresql://localhost:5432/searchess"
  $env:SEARCHESS_POSTGRES_USER = "searchess"
  $env:SEARCHESS_POSTGRES_PASSWORD = "searchess"
  $env:SEARCHESS_MONGO_URI = "mongodb://localhost:27017/searchess"
}

function Assert-PostgresContainsGame {
  param([string]$GameId)

  Write-Step "Verifying Postgres contains game $GameId"
  $sql = "select count(*) from sessions s join game_states g on g.game_id = s.game_id where s.game_id = '$GameId';"
  $output = Invoke-CommandChecked -File "docker" -Arguments @(
    "compose", "exec", "-T", "postgres",
    "psql", "-U", "searchess", "-d", "searchess", "-t", "-A", "-c", $sql
  )
  $count = ($output -split "\r?\n" | Where-Object { $_ -match "^\d+$" } | Select-Object -Last 1)
  Assert-True ($count -eq "1") "Expected Postgres to contain one session/game_state aggregate for $GameId, got '$count'."
  Write-Host "Postgres contains the created session and game_state rows."
}

function Assert-LoadedGame {
  param(
    [object]$Game,
    [string]$GameId
  )

  $loadedGameId = Get-FirstNestedValue -Object $Game -Paths @("gameId", "game.gameId")
  Assert-True ([string]$loadedGameId -eq $GameId) "Loaded gameId '$loadedGameId' did not match created gameId '$GameId'."

  $history = Get-FirstNestedValue -Object $Game -Paths @("moveHistory", "game.moveHistory")
  Assert-True ($null -ne $history) "Loaded game did not include moveHistory."

  $moveJson = ($history | ConvertTo-Json -Depth 16 -Compress).ToLowerInvariant()
  Assert-True ($moveJson.Contains("e2") -and $moveJson.Contains("e4")) "Loaded moveHistory did not contain e2 -> e4."

  Write-Host "Loaded game $GameId with moveHistory containing e2 -> e4."
}

try {
  Write-Step "Preparing clean persistence migration demo"
  if ($NoReset) {
    Write-Host "Skipping volume reset because -NoReset was supplied."
  } else {
    Write-Host "Resetting Docker Compose containers and volumes for a clean demo."
    Invoke-CommandChecked -File "docker" -Arguments @("compose", "down", "-v", "--remove-orphans")
  }

  Write-Step "Starting Docker Compose stack"
  $upArgs = @("compose", "up", "-d")
  if (-not $SkipBuild) {
    $upArgs += "--build"
  }
  Invoke-CommandChecked -File "docker" -Arguments $upArgs
  Wait-ForStack

  Write-Step "Confirming Game Service starts on Postgres"
  Assert-GameServicePersistenceLog -Expected "Postgres"

  Write-Step "Creating HumanVsHuman session through Envoy"
  $created = Invoke-ApiJson -Method "Post" -Path "/sessions" -Body @{ mode = "HumanVsHuman" }
  $createdGameId = Get-FirstNestedValue -Object $created -Paths @("session.gameId", "game.gameId", "gameId")
  Assert-True (-not [string]::IsNullOrWhiteSpace([string]$createdGameId)) "Could not find gameId in create-session response."
  Write-Host "Created gameId: $createdGameId"

  Write-Step "Submitting move e2 -> e4"
  $moveResult = Invoke-ApiJson -Method "Post" -Path "/games/$createdGameId/moves" -Body @{
    from       = "e2"
    to         = "e4"
    controller = "HumanLocal"
  }
  Assert-LoadedGame -Game $moveResult -GameId $createdGameId

  Assert-PostgresContainsGame -GameId $createdGameId

  Set-MigrationEnvironment

  Invoke-Migration `
    -Label "Running migration dry-run Postgres -> Mongo" `
    -MigrationArguments @("--from", "postgres", "--to", "mongo", "--mode", "dry-run") | Out-Null

  Invoke-Migration `
    -Label "Running migration execute Postgres -> Mongo with validate-after-execute" `
    -MigrationArguments @("--from", "postgres", "--to", "mongo", "--mode", "execute", "--validate-after-execute") `
    -ExpectValidationPassed | Out-Null

  Write-Step "Switching Game Service runtime to Mongo"
  $env:PERSISTENCE_MODE = "mongo"
  Invoke-CommandChecked -File "docker" -Arguments @("compose", "up", "-d", "--force-recreate", "game-service")
  Wait-ForComposeService -Service "game-service"
  Assert-GameServicePersistenceLog -Expected "Mongo"

  Write-Step "Loading migrated game through Mongo runtime"
  $loaded = Invoke-ApiJson -Method "Get" -Path "/games/$createdGameId"
  Assert-LoadedGame -Game $loaded -GameId $createdGameId

  Invoke-Migration `
    -Label "Running migration execute again to prove idempotency" `
    -MigrationArguments @("--from", "postgres", "--to", "mongo", "--mode", "execute", "--validate-after-execute") `
    -ExpectValidationPassed `
    -ExpectSkippedEquivalent | Out-Null

  if ($ReturnToPostgres) {
    Write-Step "Returning Game Service runtime to the Postgres default"
    if ($null -eq $previousPersistenceMode) {
      Remove-Item Env:PERSISTENCE_MODE -ErrorAction SilentlyContinue
    } else {
      $env:PERSISTENCE_MODE = $previousPersistenceMode
    }
    Invoke-CommandChecked -File "docker" -Arguments @("compose", "up", "-d", "--force-recreate", "game-service")
    Wait-ForComposeService -Service "game-service"
    Assert-GameServicePersistenceLog -Expected "Postgres"
  } else {
    Write-Host ""
    Write-Host "Leaving Game Service on Mongo runtime. Use -ReturnToPostgres to restore the default automatically."
  }

  Write-Step "Demo completed successfully"
  Write-Host "Created and migrated gameId: $createdGameId"
  Write-Host "Verified: Postgres write, dry-run success, execute validation passed, Mongo runtime load, idempotent rerun."
} finally {
  if (-not $ReturnToPostgres -and -not $LeaveMongo) {
    Write-Host ""
    Write-Host "Note: Game Service is intentionally left on Mongo runtime after the demo."
  }
}
