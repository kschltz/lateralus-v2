# lateralus Docker

Ships the JVM uberjar (workbench included) next to a local Ollama.

## Quick start

One-liner (macOS / Linux / WSL / Git Bash):

```bash
./scripts/start-workbench
```

Windows PowerShell:

```powershell
.\scripts\start-workbench.ps1
```

Manual steps:

```bash
# 1) Start Ollama
docker compose up -d ollama

# 2) Pull the default model (once)
docker compose --profile setup run --rm pull-model

# 3) Interactive lateralus (profile gate + REPL); workbench on :7860
docker compose run --rm --service-ports lateralus
```

Open **http://localhost:7860** for CHAT | Portal (Portal iframe on **:7870**).

At the profile gate, Enter keeps the seeded `docker` profile
(local Ollama at `http://ollama:11434/v1`, workbench on). Use `?` / `/term`
on the Model prompt to search catalogs.

## Useful knobs

| Env | Default | Purpose |
|-----|---------|---------|
| `LATERALUS_MODEL` | `llama3.2` (seed only) | Used when seeding a fresh config volume / by `pull-model` |
| `LATERALUS_BASE_URL` | _(unset at runtime)_ | Optional fill-in when a profile has no `:base-url`; **do not** set this to local Ollama if you want Cloud |
| `OLLAMA_API_KEY` | _(empty)_ | Required for Ollama Cloud |
| `LATERALUS_WORKBENCH_HOST` | `0.0.0.0` | Bind address inside the container |
| `LATERALUS_WORKBENCH_PUBLIC_HOST` | `localhost` | Host printed in UI links |
| `LATERALUS_PORTAL_PORT` | `7870` | Portal server port (published to the host) |
| `LATERALUS_CONFIG_HOME` | `/data/config` | Profile store inside the container |

```bash
LATERALUS_MODEL=qwen2.5:7b docker compose --profile setup run --rm pull-model
LATERALUS_MODEL=qwen2.5:7b docker compose run --rm --service-ports lateralus
```

## Ollama Cloud inside Docker

Export a key and pick the **ollama-cloud** starter (or edit the profile) in the gate.
Profile settings win over compose env, so a cloud profile is not forced back onto
the local Ollama URL.

```bash
OLLAMA_API_KEY=… ./scripts/start-workbench
# profile gate → starter 3 (ollama-cloud) → Model: /deepseek → pick id
```

Do **not** set `LATERALUS_BASE_URL=http://ollama:11434/v1` when using Cloud — that
sends “cloud” model ids to the local container, which often dies with
`llama-server … signal: killed` (OOM).

## Build only

```bash
docker compose build lateralus
# or
docker build -t lateralus:local .
```

Profiles persist in the `lateralus-config` volume (`/data/config` in the container).
