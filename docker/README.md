# lateralus Docker

Ships the JVM uberjar (workbench included). By default it **references host
Ollama over the network** — no copying or mounting `~/.ollama`.

## Quick start

```bash
# Host: Ollama Desktop (or `ollama serve`) with your models pulled
./scripts/start-workbench
```

Windows PowerShell: `.\scripts\start-workbench.ps1`

`start-workbench` will:

1. Stop compose `ollama` if it was stealing `:11434`
2. Point the lateralus container at `http://host.docker.internal:11434/v1`
3. Run with `--no-deps` (no in-Docker model store)

Your Desktop pulls (`laguna-s-2.1`, `ornith:35b`, …) appear in Model `?`.

Fallback (no host daemon): compose starts an isolated `ollama` volume and pulls
`LATERALUS_MODEL` (default `llama3.2`). Force that path with
`LATERALUS_FORCE_DOCKER_OLLAMA=1`.

Open **http://localhost:7860** for CHAT | Portal (Portal iframe on **:7870**).

Remote viewers (Tailscale MagicDNS, LAN IP): set
`LATERALUS_WORKBENCH_HOST=0.0.0.0` so both CHAT and Portal bind on all
interfaces, publish **7860** and **7870**, then open the workbench via the
MagicDNS name. The Portal iframe host follows the browser `Host` header
(so it is not stuck on `localhost`).

## Useful knobs

| Env | Default | Purpose |
|-----|---------|---------|
| `LATERALUS_MODEL` | `llama3.2` | Host ensure-pull / compose `pull-model` |
| `LATERALUS_DOCKER_OLLAMA_URL` | host or `http://ollama:11434/v1` | Rewrite target for localhost:11434 inside Docker |
| `LATERALUS_FORCE_DOCKER_OLLAMA` | `0` | `1` = always use compose Ollama |
| `LATERALUS_LIST_CLOUD` | `0` | `1` = merge Ollama Cloud ids into local `?` in Docker |
| `LATERALUS_WORKBENCH_HOST` | `0.0.0.0` (Docker) | Bind address for CHAT + Portal |
| `LATERALUS_WORKBENCH_PUBLIC_HOST` | `localhost` (Docker) | Startup advertise host only; runtime iframe uses request Host |
| `LATERALUS_PORTAL_PORT` | `7870` (Docker) | Portal HTTP port (must be reachable by the browser) |
| `OLLAMA_API_KEY` | _(empty)_ | Required for Ollama Cloud profiles |
| `LATERALUS_CONFIG_HOME` | `/data/config` | Profile store inside the container |

## Ollama Cloud inside Docker

```bash
OLLAMA_API_KEY=… ./scripts/start-workbench
# profile gate → ollama-cloud → Model: /deepseek → pick id
```

Huge local weights can OOM inside Docker Desktop’s memory limit; host Ollama
+ Metal is the intended path for 35B+ models.

## Build only

```bash
docker compose build lateralus
```

Profiles persist in the `lateralus-config` volume.
