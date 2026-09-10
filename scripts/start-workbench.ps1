# Start lateralus workbench: local Clojure or Docker.
# Default: host Ollama over host.docker.internal (no model mount/copy).
[CmdletBinding()]
param(
  [ValidateSet("local", "docker")]
  [string]$Mode,
  [switch]$Local,
  [switch]$Docker,
  [ValidateSet("memory", "duckdb")]
  [string]$Store,
  [switch]$DryRun,
  [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
  Write-Host @"
Usage: .\scripts\start-workbench.ps1 [-Local] [-Docker] [-Mode local|docker] [-Store memory|duckdb] [-DryRun]

  -Local / -Docker   Choose runtime (skips the prompt)
  -Mode              Same as -Local / -Docker
  -Store             LATERALUS_STORE
  -DryRun            Print the chosen command and exit

On an interactive console with no runtime flag, the script asks 1=local / 2=docker.
"@
  exit 0
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

$Model = if ($env:LATERALUS_MODEL) { $env:LATERALUS_MODEL } else { "llama3.2" }
$Runtime = $env:LATERALUS_WORKBENCH_RUNTIME
if ($Local) { $Runtime = "local" }
if ($Docker) { $Runtime = "docker" }
if ($Mode) { $Runtime = $Mode }
if ($Store) { $env:LATERALUS_STORE = $Store }

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

function Get-SrcFingerprint {
  $files = Get-ChildItem -Path src, resources -Recurse -File |
    Where-Object { $_.Name -ne ".DS_Store" } |
    Sort-Object FullName
  $sha = [System.Security.Cryptography.SHA256]::Create()
  foreach ($f in $files) {
    $rel = [System.Text.Encoding]::UTF8.GetBytes(($f.FullName.Substring($Root.Path.Length + 1) -replace '\\', '/'))
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    [void]$sha.TransformBlock($rel, 0, $rel.Length, $null, 0)
    [void]$sha.TransformBlock($bytes, 0, $bytes.Length, $null, 0)
  }
  [void]$sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
  return ([System.BitConverter]::ToString($sha.Hash) -replace '-', '').ToLowerInvariant()
}

function Stop-OldWorkbench {
  Write-Host "==> stopping any workbench already publishing :7860 or :7870"
  $ids = @(
    docker ps -q --filter publish=7860
    docker ps -q --filter publish=7870
  ) | Where-Object { $_ } | Select-Object -Unique
  if ($ids.Count -gt 0) {
    docker stop @ids
  } else {
    Write-Host "    none running"
  }
}

function Get-WorkbenchRuntime {
  if ($Runtime -eq "local" -or $Runtime -eq "docker") { return $Runtime }
  if ($Runtime -and $Runtime -ne "") {
    throw "unknown LATERALUS_WORKBENCH_RUNTIME '$Runtime' (expected local | docker)"
  }
  if ([Console]::IsInputRedirected) {
    Write-Host "note: no TTY — using Docker (pass -Local or -Docker to choose)"
    return "docker"
  }
  $default = 2
  if (Get-Command clojure -ErrorAction SilentlyContinue) { $default = 1 }
  Write-Host ""
  Write-Host "How do you want to run the Lateralus workbench?"
  Write-Host ""
  Write-Host "  1) Local Clojure   — Java 22+ on this machine; repo is the workspace"
  Write-Host "  2) Docker          — uberjar in Compose; checkout mounted at /workspace"
  Write-Host ""
  $answer = Read-Host "Choice [$default]"
  if (-not $answer) { $answer = "$default" }
  switch -Regex ($answer) {
    '^(1|local|clojure|l)$' { return "local" }
    '^(2|docker|d)$' { return "docker" }
    default { throw "unknown choice '$answer' (expected 1 or 2)" }
  }
}

function Start-LocalWorkbench {
  if (-not (Get-Command clojure -ErrorAction SilentlyContinue)) {
    throw "clojure CLI not found. Install Clojure or use -Docker."
  }
  Write-Host "==> runtime: local Clojure"
  Write-Host "    Command: clojure -M:workbench:run -i"
  Write-Host "    Workspace: $Root"
  Write-Host "    Workbench UI: http://localhost:7860"
  if ($DryRun) {
    Write-Host "mode=local"
    Write-Host "workspace=$Root"
    Write-Host "command=clojure -M:workbench:run -i"
    return
  }
  Stop-OldWorkbench
  Start-Job -ScriptBlock {
    Start-Sleep -Seconds 4
    Start-Process "http://localhost:7860"
  } | Out-Null
  & clojure -M:workbench:run -i
  exit $LASTEXITCODE
}

$chosen = Get-WorkbenchRuntime
if ($chosen -eq "local") {
  Start-LocalWorkbench
  return
}

$env:LATERALUS_WORKSPACE = if ($env:LATERALUS_WORKSPACE) { $env:LATERALUS_WORKSPACE } else { "$Root" }

if ($DryRun) {
  Write-Host "==> runtime: Docker"
  Write-Host "mode=docker"
  Write-Host "workspace=$($env:LATERALUS_WORKSPACE)"
  Write-Host "command=docker compose run --rm --service-ports lateralus -i"
  return
}

Write-Host "==> checking Docker"
docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Docker is not running. Start Docker Desktop and retry."
}

Stop-OldWorkbench

$env:LATERALUS_SRC_REV = Get-SrcFingerprint
Write-Host "==> building lateralus image from current tree (src-rev $($env:LATERALUS_SRC_REV.Substring(0, 12)))"
$buildArgs = @("build", "--build-arg", "LATERALUS_SRC_REV=$($env:LATERALUS_SRC_REV)")
if ($env:LATERALUS_DOCKER_NO_CACHE -eq "1") {
  $buildArgs += "--no-cache"
}
Invoke-Compose @buildArgs lateralus
$imageInfo = docker image inspect lateralus-v2-lateralus:latest --format "{{.Id}} {{.Created}}" 2>$null
Write-Host "==> image $imageInfo"

$useHost = $false
$runArgs = @("run", "--rm", "--service-ports", "--build")

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
Write-Host "    Workspace: /workspace (host $($env:LATERALUS_WORKSPACE))"
Write-Host ""

Start-Job -ScriptBlock {
  Start-Sleep -Seconds 4
  Start-Process "http://localhost:7860"
} | Out-Null

$env:LATERALUS_MODEL = $Model
docker compose @runArgs lateralus -i
exit $LASTEXITCODE
