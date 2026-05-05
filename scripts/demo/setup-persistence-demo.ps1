param(
  [switch]$NoReset,
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

$edgeHealthUrl = "http://127.0.0.1:10000/health"

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
    if ($Quiet) {
      foreach ($line in $output) {
        Write-Host $line
      }
    }
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
  param(
    [string]$Expected,
    [switch]$RuntimeSwitch
  )

  $logs = Invoke-CommandChecked -File "docker" -Arguments @("compose", "logs", "--no-color", "--tail=120", "game-service") -Quiet
  $needle = '"persistence":"' + $Expected + '"'
  $ok = [char]0x2714
  Assert-True ($logs.Contains($needle)) "Expected game-service logs to contain $needle."
  Write-Host "Game Service is running with $Expected persistence."
  if ($RuntimeSwitch) {
    Write-Host "$ok Runtime successfully switched to $Expected"
  }
  Write-Host "$ok Verified via game-service logs"
  Write-Host "Matched log field: $needle"
}

Write-Step "Preparing persistence demo environment"
if ($NoReset) {
  Write-Host "Skipping volume reset because -NoReset was supplied."
} else {
  Write-Host "Resetting Docker Compose containers and volumes for a clean demo."
  Write-Host "> docker compose down -v --remove-orphans"
  Invoke-CommandChecked -File "docker" -Arguments @("compose", "down", "-v", "--remove-orphans") -Quiet | Out-Null
}

Write-Step "Starting Docker Compose stack"
$upArgs = @("compose", "up", "-d")
if (-not $SkipBuild) {
  $upArgs += "--build"
}
Write-Host "> docker $($upArgs -join ' ')"
Invoke-CommandChecked -File "docker" -Arguments $upArgs -Quiet | Out-Null

Wait-ForStack

Write-Step "Confirming Game Service starts with Postgres persistence"
Assert-GameServicePersistenceLog -Expected "Postgres"

Write-Step "Persistence demo environment is ready"
Write-Host "Backend is running on Postgres through Envoy at http://127.0.0.1:10000/api."
Write-Host "You can now start the Web UI manually for the presentation."
