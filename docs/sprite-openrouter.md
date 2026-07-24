# Running lateralus-v2 on a Fly.io Sprite (OpenRouter free models)

A Sprite is a persistent, hardware-isolated Linux microVM on Fly.io that
boots in ~1s, sleeps when idle, and keeps its filesystem between sessions.
This runbook puts lateralus-v2 on one, talking to OpenRouter's `:free`
models over the public internet.

Companion config: `resources/lateralus/openrouter-free.edn` — HTTP LLM
(`https://openrouter.ai/api/v1`) + KG-BM25 memory + noop embedder, so it
boots without the Proximum/LangChain4j ONNX stack.

## Status of the `lateralus` Sprite (already set up)

- **Sprite name:** `lateralus` (org `kaue-schultz`)
- **URL:** https://lateralus-kvfw.sprites.app
- **OS:** Ubuntu 26.04 LTS, with Temurin **OpenJDK 25** preinstalled at
  `/.sprite/bin/java` (`jdk.incubator.vector@25` present — satisfies
  lateralus's Java 22+ requirement, no install needed).
- **Clojure CLI 1.12.5.1654** installed at `/usr/local/lib/clojure`,
  symlinked into `/usr/local/bin/{clojure,clj}`.
- **lateralus-v2** unpacked at `~/lateralus-v2`; Maven deps prefetched
  (`~/.m2` ≈ 242 MB).
- **Checkpoints:** `v2` = ready to run (needs OpenRouter key); `v1` = clean
  toolchain before source was added.

The toolchain install is captured in checkpoints, so on a fresh Sprite
you can `sprite restore v2` and skip steps 1–4 below.

## From-scratch setup (if you ever rebuild the Sprite)

### 0. Local prerequisites

```bash
curl -fsSL https://sprites.dev/install.sh | sh   # install sprite CLI
sprite org auth                                   # browser auth on Fly.io
```

### 1. Create the Sprite

```bash
sprite create lateralus --skip-console
sprite use lateralus
sprite info -s lateralus     # note the URL + region
```

### 2. Java — already present

The base image ships Temurin 25 with `jdk.incubator.vector@25`. Verify:

```bash
sprite exec -s lateralus -- java --list-modules | grep incubator
```

### 3. Clojure CLI — bundle from a machine that has it

The documented `github.com/clojure/brew-installer` install path **returns
404 in this environment** (the repo is unreachable/removed). The CLI is
self-contained though: a `clojure` wrapper script + `clojure-tools-<ver>.jar`
+ `exec.jar` + three `.edn` files. Bundle them from a machine with a working
Homebrew/install, retarget `install_dir` to a Linux prefix, and ship:

```bash
# On the machine that HAS clojure (e.g. your mac):
SRC=/opt/homebrew/Cellar/clojure/1.12.5.1654   # adjust to your install
VER=1.12.5.1654
DEST=/tmp/clojure-cli-linux
LINUX_PREFIX=/usr/local/lib/clojure
rm -rf "$DEST" && mkdir -p "$DEST/libexec" "$DEST/bin"
cp "$SRC/deps.edn" "$SRC/example-deps.edn" "$SRC/tools.edn" "$DEST/"
cp "$SRC/libexec/clojure-tools-$VER.jar" "$SRC/libexec/exec.jar" "$DEST/libexec/"
sed "s#install_dir=/opt/homebrew/Cellar/clojure/$VER#install_dir=$LINUX_PREFIX#" \
    "$SRC/libexec/bin/clojure" > "$DEST/bin/clojure"
chmod +x "$DEST/bin/clojure"
cat > "$DEST/bin/clj" <<EOF
#!/usr/bin/env bash
bin_dir=$LINUX_PREFIX/bin
if type -p rlwrap >/dev/null 2>&1; then
  exec rlwrap -m -r -q '\\"' -b "(){}[],^%#@;:'" "\$bin_dir/clojure" "\$@"
else
  exec "\$bin_dir/clojure" "\$@"
fi
EOF
chmod +x "$DEST/bin/clj"
cat > "$DEST/install.sh" <<EOF
#!/usr/bin/env bash
set -e
PREFIX=$LINUX_PREFIX
SD=\$(cd "\$(dirname "\$0")" && pwd)
sudo mkdir -p "\$PREFIX/libexec" "\$PREFIX/bin"
sudo cp -r "\$SD"/libexec/* "\$PREFIX/libexec/"
sudo cp "\$SD"/deps.edn "\$SD"/example-deps.edn "\$SD"/tools.edn "\$PREFIX/"
sudo cp "\$SD"/bin/clojure "\$SD"/bin/clj "\$PREFIX/bin/"
sudo chmod +x "\$PREFIX/bin/clojure" "\$PREFIX/bin/clj"
sudo ln -sf "\$PREFIX/bin/clojure" /usr/local/bin/clojure
sudo ln -sf "\$PREFIX/bin/clj" /usr/local/bin/clj
clojure --version
EOF
chmod +x "$DEST/install.sh"
tar czf /tmp/clojure-cli-linux.tar.gz -C /tmp clojure-cli-linux

# Ship + install on the Sprite:
sprite file push -s lateralus /tmp/clojure-cli-linux.tar.gz /tmp/clojure-cli-linux.tar.gz
sprite exec -s lateralus -- bash -lc 'cd /tmp && tar xzf clojure-cli-linux.tar.gz && cd clojure-cli-linux && ./install.sh'
```

> Note: `sprite file push` to an existing remote path can leave a stale
> copy — verify the remote md5 matches before extracting, or push to a
> fresh filename.

### 4. Sync lateralus-v2 source

```bash
cd /path/to/lateralus-v2
tar czf /tmp/lateralus-v2.tgz -C "$(pwd)" \
  src test resources deps.edn build.clj scripts docker docker-compose.yml \
  docs goals dev README.md AGENT_INSTRUCTIONS.md AGENTS.md CLAUDE.md CHANGELOG.md LICENSE local-ai
sprite file push -s lateralus /tmp/lateralus-v2.tgz /tmp/lateralus-v2.tgz
sprite exec -s lateralus -- bash -lc '
  rm -rf ~/lateralus-v2 && mkdir -p ~/lateralus-v2
  tar xzf /tmp/lateralus-v2.tgz -C ~/lateralus-v2
  cd ~/lateralus-v2 && clojure -P -M:run -Sforce'
```

> **tar exclude pitfall:** `--exclude=portal` matches *any* path
> component named `portal`, including `src/kschltz/agent/portal/` (the
> portal schemas namespace that `system.clj` requires unconditionally).
> That strips a required namespace and the agent fails at boot with
> `Could not locate kschltz/agent/portal/schemas`. Tar explicit paths
> instead (as above), or anchor excludes and verify with
> `tar tzf ... | grep agent/portal/schemas.clj`.

### 5. Checkpoint before letting the agent loose

```bash
sprite checkpoint create -s lateralus --comment "toolchain + source + deps ready"
```

## Running the agent

### Set the OpenRouter key

Get one at https://openrouter.ai/keys. The lateralus CLI reads
`OLLAMA_API_KEY` as its env api-key fallback (confusingly named for an
OpenRouter key, but it's the only env channel). Persist it in the Sprite
profile so it survives sleeps/restores:

```bash
sprite exec -s lateralus -- bash -lc 'echo "export OLLAMA_API_KEY=sk-or-v1-REDACTED" >> ~/.profile'
```

> Keep the key in env, not in `openrouter-free.edn` — the Sprite
> filesystem is durable and checkpointed.

### One-shot

```bash
sprite exec -s lateralus -- bash -lc '
  cd ~/lateralus-v2
  clojure -M:run --config resources/lateralus/openrouter-free.edn \
    "Explain how the interceptor chain works in this codebase"'
```

### Interactive

```bash
sprite console -s lateralus
# inside the Sprite:
cd ~/lateralus-v2
clojure -M:run --config resources/lateralus/openrouter-free.edn -i
```

### Switch free models

```bash
sprite exec -s lateralus -- bash -lc 'cd ~/lateralus-v2 && clojure -M:run \
  --config resources/lateralus/openrouter-free.edn \
  --model openrouter/poolside/laguna-m.1:free \
  "Hello"'
```

## Serving the workbench from the Sprite (public CHAT + Portal, with basic auth)

The Sprite proxy only offers `public` (no auth) or `sprite` (Fly.io org login) —
no built-in user:pass. To get a simple user:pass gate for sharing, run a tiny
HTTP Basic-Auth reverse proxy in front of the workbench, and set the Sprite
URL to `public`. Portal works through the single proxied port because Portal's
`/main.js`, `/rpc`, `/vendor`, `/?<session-uuid>` are delegated to Portal's
in-process handler on the CHAT server (same-origin, since commit `5abdd4b`),
and the iframe URL is rewritten from the browser `Host`/`x-forwarded-proto`.

```
browser -> Sprite proxy (TLS) -> auth-proxy :8080 (basic auth) -> workbench 127.0.0.1:8081
```

Files:
- `resources/lateralus/openrouter-free-workbench.edn` — openrouter-free +
  `:lateralus/workbench {:host "127.0.0.1" :port 8081 :portal? true
  :open-browser? false}` + workbench-plugin wired into `:lateralus/plugins`
  and `:lateralus/agent :workbench`. `:portal? true` serves Portal
  same-origin on the CHAT port (no second public port needed).
- `scripts/sprite-auth-proxy.js` — zero-dependency Node Basic-Auth reverse
  proxy. Forwards the original `Host` + `x-forwarded-proto` (not the upstream
  host) so the workbench rewrites the Portal iframe URL to the browser-visible
  origin. Env: `AUTH_USER`, `AUTH_PASS` (required), `UPSTREAM`
  (default `http://127.0.0.1:8081`), `PORT` (default 8080), `REALM`.

### Register both as managed services

```bash
# Workbench on loopback 8081 (no --http-port — not directly exposed)
sprite-env services create lateralus-workbench \
  --cmd /usr/local/bin/clojure \
  --args "-M:workbench:run,-i,--config,resources/lateralus/openrouter-free-workbench.edn" \
  --dir /home/sprite/lateralus-v2 \
  --env "OLLAMA_API_KEY=sk-or-v1-...,LATERALUS_WORKBENCH_PUBLIC_HOST=<sprite-host>"

# Basic-auth proxy on 0.0.0.0:8080 (the Sprite-proxied port), depends on workbench
sprite-env services create lateralus-auth-proxy \
  --cmd /.sprite/bin/node --args scripts/sprite-auth-proxy.js \
  --dir /home/sprite/lateralus-v2 \
  --env "AUTH_USER=lateralus,AUTH_PASS=<your-password>,UPSTREAM=http://127.0.0.1:8081,PORT=8080" \
  --needs lateralus-workbench --http-port 8080

# Make the Sprite URL public so friends can reach the basic-auth gate
sprite url update --auth public -s lateralus
```

Only the `lateralus-auth-proxy` service holds `--http-port` (one max). The
workbench binds loopback and is unreachable directly. Manage with
`sprite-env services list|get|stop|start|restart|delete`. Logs at
`/.sprite/logs/services/<name>.log`.

### Rotate the user:pass

Service env is set at create time only — to change credentials, delete +
recreate the proxy service:

```bash
sprite-env services delete lateralus-auth-proxy
sprite-env services create lateralus-auth-proxy \
  --cmd /.sprite/bin/node --args scripts/sprite-auth-proxy.js \
  --dir /home/sprite/lateralus-v2 \
  --env "AUTH_USER=<new-user>,AUTH_PASS=<new-pass>,UPSTREAM=http://127.0.0.1:8081,PORT=8080" \
  --needs lateralus-workbench --http-port 8080
```

### Browser access for friends

Share `https://<sprite-name>-<org>.sprites.app/` plus the user:pass. The
browser prompts for Basic Auth; on success it loads the CHAT UI (left) with
the Portal pane (right) same-origin. No Fly.io login needed by viewers.
Both panes live-update over `/api/events` SSE (fixed).

## Rollback

```bash
sprite checkpoint list -s lateralus
sprite restore -s lateralus v2
```

## Gotchas

- **Free models + tools are flaky.** OpenRouter `:free` models frequently
  ignore `tool_choice` or emit empty-content tool calls (memory:
  `lateralus-v2-r3-tool-choice-none-not-honored`). The summary mini-chain
  strips `:tools` defensively, but if the agent stalls on empty assistant
  turns, switch models with `--model`. Verified tool-capable free models
  are listed in the config header.
- **Default model pick:** `openrouter/nvidia/nemotron-3-super-120b-a12b:free`
  (256K ctx, 12B-active MoE, reasoning on) — best capability/latency
  balance for an agent loop. The 550B ultra is more capable but gets
  throttled on the free tier.
- **No Ollama needed.** Don't set `LATERALUS_BASE_URL` to a local Ollama;
  the config pins OpenRouter. Don't set `LATERALUS_IN_DOCKER=1` — that
  triggers the Docker-only Ollama base-url rewrite in `resolve-base-url`.
- **`sprite file push` staleness:** pushing to an existing remote path
  has been observed to leave the old bytes in place; verify md5 after
  push or push to a new filename.
- **Cost:** Sprites bill for storage blocks written + active CPU. Free
  OpenRouter models cost nothing on the model side (subject to rate
  limits). A sleeping Sprite ≈ negligible.