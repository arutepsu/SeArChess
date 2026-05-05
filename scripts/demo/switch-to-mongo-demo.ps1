param(
  [switch]$SkipDryRun
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

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
    if ($Quiet) {
      foreach ($line in $output) {
        Write-Host $line
      }
    }
    throw "Command failed with exit code ${exitCode}: $commandLine"
  }

  return ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
}

function Set-MigrationEnvironment {
  $env:SEARCHESS_POSTGRES_URL = "jdbc:postgresql://localhost:5432/searchess"
  $env:SEARCHESS_POSTGRES_USER = "searchess"
  $env:SEARCHESS_POSTGRES_PASSWORD = "searchess"
  $env:SEARCHESS_MONGO_URI = "mongodb://localhost:27017/searchess"
}

function Invoke-Migration {
  param(
    [string]$Label,
    [string[]]$MigrationArguments,
    [switch]$ExpectValidationPassed
  )

  Write-Step $Label
  $sbtCommand = "gameService/runMain chess.server.migration.PersistenceMigrationMain $($MigrationArguments -join ' ')"
  $output = Invoke-CommandChecked -File "sbt" -Arguments @($sbtCommand)

  Assert-True ($output -match "Status:\s+Success") "$Label did not report Status: Success."
  Write-Host "Migration report status: Success"

  if ($ExpectValidationPassed) {
    Assert-True ($output -match "Validation result:\s+Passed") "$Label did not report Validation result: Passed."
    Write-Host "Migration validation result: Passed"
  }

  return $output
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

function Assert-GameServicePersistenceLog {
  param([string]$Expected)

  $logs = Invoke-CommandChecked -File "docker" -Arguments @("compose", "logs", "--no-color", "--tail=120", "game-service") -Quiet
  $needle = '"persistence":"' + $Expected + '"'
  $ok = [char]0x2714
  Assert-True ($logs.Contains($needle)) "Expected game-service logs to contain $needle."
  Write-Host "Game Service is running with $Expected persistence."
  Write-Host "$ok Runtime successfully switched to $Expected"
  Write-Host "$ok Verified via game-service logs"
  Write-Host "Matched log field: $needle"
}

Write-Step "Preparing migration environment"
Set-MigrationEnvironment
Write-Host "SEARCHESS_POSTGRES_URL=$env:SEARCHESS_POSTGRES_URL"
Write-Host "SEARCHESS_POSTGRES_USER=$env:SEARCHESS_POSTGRES_USER"
Write-Host "SEARCHESS_MONGO_URI=$env:SEARCHESS_MONGO_URI"

if ($SkipDryRun) {
  Write-Step "Skipping dry-run migration"
  Write-Host "Skipping dry-run because -SkipDryRun was supplied."
} else {
  Invoke-Migration `
    -Label "Running migration dry-run Postgres -> Mongo" `
    -MigrationArguments @("--from", "postgres", "--to", "mongo", "--mode", "dry-run") | Out-Null
}

Invoke-Migration `
  -Label "Running migration execute Postgres -> Mongo with validate-after-execute" `
  -MigrationArguments @("--from", "postgres", "--to", "mongo", "--mode", "execute", "--validate-after-execute") `
  -ExpectValidationPassed | Out-Null

Write-Step "Switching Game Service runtime to Mongo"
$env:PERSISTENCE_MODE = "mongo"
Invoke-CommandChecked -File "docker" -Arguments @("compose", "up", "-d", "--force-recreate", "game-service")
Wait-ForComposeService -Service "game-service"
Assert-GameServicePersistenceLog -Expected "Mongo"

Write-Step "Mongo runtime is ready"
Write-Host "Migration complete. Game Service is now running with Mongo persistence."
Write-Host "Return to the Web UI and load the same session from the main menu."
