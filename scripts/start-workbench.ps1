# Start lateralus workbench (Docker): interactive profile setup, then CHAT | Portal.
# Default: host Ollama over host.docker.internal (no model mount/copy).
$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

$Model = if ($env:LATERALUS_MODEL) { $env:LATERALUS_MODEL } else { "llama3.2" }

function Invoke-Compose {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  & docker compose @Args
  if ($LASTEXITCODE -ne 0) { throw "docker compose $($Args -join ' ') failed ($LASTEXITCODE)" }
}

function Test-HostOllama {
  try {
    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:11434/api/tags" -UseBasicParsing -TimeoutSec 2
    return ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300)
  } catch {
    return $false
  }
}

function Test-ComposeOllamaRunning {
  $services = docker compose ps --status running --services 2>$null
  return [bool]($services -split "`n" | Where-Object { $_.Trim() -eq "ollama" })
}

function Wait-HostOllama {
  for ($i = 1; $i -le 45; $i++) {
    if (Test-HostOllama) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}

Write-Host "==> checking Docker"
docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Docker is not running. Start Docker Desktop and retry."
}

Write-Host "==> building lateralus image from current tree"
Invoke-Compose build lateralus

$useHost = $false
$runArgs = @("run", "--rm", "--service-ports")

if ($env:LATERALUS_FORCE_DOCKER_OLLAMA -ne "1") {
  if (Test-ComposeOllamaRunning) {
    Write-Host "==> stopping compose Ollama (will use host daemon instead of mounting models)"
    docker compose stop ollama | Out-Null
  }

  if (-not (Wait-HostOllama)) {
    Write-Host "==> host Ollama not reachable on :11434 — falling back to compose Ollama" -ForegroundColor Yellow
  } else {
    $useHost = $true
    if (-not $env:LATERALUS_DOCKER_OLLAMA_URL) {
      $env:LATERALUS_DOCKER_OLLAMA_URL = "http://host.docker.internal:11434/v1"
    }
    $runArgs += "--no-deps"
    Write-Host "==> using host Ollama via $($env:LATERALUS_DOCKER_OLLAMA_URL)"
    Write-Host "    No model copy/mount — lateralus calls your host pulls over the network."
    if (Get-Command ollama -ErrorAction SilentlyContinue) {
      Write-Host "==> host models:"
      & ollama list
    }
  }
}

if (-not $useHost) {
  if (-not $env:LATERALUS_DOCKER_OLLAMA_URL) {
    $env:LATERALUS_DOCKER_OLLAMA_URL = "http://ollama:11434/v1"
  }
  Write-Host "==> starting compose Ollama (isolated volume)"
  Invoke-Compose up -d ollama

  Write-Host "==> waiting for Ollama"
  $ready = $false
  for ($i = 1; $i -le 60; $i++) {
    docker compose exec -T ollama ollama list 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $ready) { throw "Ollama did not become ready in time" }

  Write-Host "==> ensuring model '$Model' is available"
  $env:LATERALUS_MODEL = $Model
  Invoke-Compose --profile setup run --rm pull-model
}

Write-Host ""
Write-Host "==> starting lateralus (interactive profile gate, then workbench)"
Write-Host "    Workbench UI: http://localhost:7860"
Write-Host "    Ollama URL inside container: $($env:LATERALUS_DOCKER_OLLAMA_URL)"
Write-Host ""

Start-Job -ScriptBlock {
  Start-Sleep -Seconds 4
  Start-Process "http://localhost:7860"
} | Out-Null

$env:LATERALUS_MODEL = $Model
docker compose @runArgs lateralus -i
exit $LASTEXITCODE
