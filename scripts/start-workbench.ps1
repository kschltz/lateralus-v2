# Start lateralus workbench (Docker): interactive profile setup, then CHAT | Portal.
# Windows PowerShell 5+ / PowerShell 7. Requires Docker Desktop.
$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

$Model = if ($env:LATERALUS_MODEL) { $env:LATERALUS_MODEL } else { "llama3.2" }

function Invoke-Compose {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  & docker compose @Args
  if ($LASTEXITCODE -ne 0) { throw "docker compose $($Args -join ' ') failed ($LASTEXITCODE)" }
}

Write-Host "==> checking Docker"
docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Docker is not running. Start Docker Desktop and retry."
}

Write-Host "==> starting Ollama"
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

Write-Host ""
Write-Host "==> starting lateralus (interactive profile gate, then workbench)"
Write-Host "    Workbench UI: http://localhost:7860"
Write-Host "    At the profile prompts, press Enter to keep the seeded 'docker' defaults."
Write-Host ""

Start-Job -ScriptBlock {
  Start-Sleep -Seconds 4
  Start-Process "http://localhost:7860"
} | Out-Null

$env:LATERALUS_MODEL = $Model
# Use docker compose run with TTY for interactive profile setup
docker compose run --rm --service-ports lateralus -i
exit $LASTEXITCODE
