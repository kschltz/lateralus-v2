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

Open **http://localhost:7860** for CHAT | Portal.

Portal is mounted on the same origin/port as CHAT (`/rpc`, `/main.js`,
`/?<session>`), so remote viewers (Tailscale MagicDNS, LAN) only need
**7860**. Set `LATERALUS_WORKBENCH_HOST=0.0.0.0` and open the MagicDNS
name — the iframe no longer depends on a published **7870**.

## Useful knobs

| Env | Default | Purpose |
|-----|---------|---------|
| `LATERALUS_MODEL` | `llama3.2` | Host ensure-pull / compose `pull-model` |
| `LATERALUS_DOCKER_OLLAMA_URL` | host or `http://ollama:11434/v1` | Rewrite target for localhost:11434 inside Docker |
| `LATERALUS_FORCE_DOCKER_OLLAMA` | `0` | `1` = always use compose Ollama |
| `LATERALUS_LIST_CLOUD` | `0` | `1` = merge Ollama Cloud ids into local `?` in Docker |
| `LATERALUS_WORKBENCH_HOST` | `0.0.0.0` (Docker) | Bind address for CHAT (and Portal's private server) |
| `LATERALUS_WORKBENCH_PUBLIC_HOST` | `localhost` (Docker) | Startup advertise host only; iframe uses CHAT origin |
| `LATERALUS_PORTAL_PORT` | `7870` (Docker) | Private Portal server port (optional; iframe uses :7860) |
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
