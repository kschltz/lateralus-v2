# Secrets plugin (`kschltz.agent.secrets`)

Hold secrets in the agent process so **LLM tools can use them but the
model can never read them**.

## Design: use-without-seeing

The model never holds a secret value. It references secrets by
**handle**: `{{secret:label}}` inside tool arguments. Resolution and
redaction happen inside the tool boundary:

1. **Capability wrap (`:guard` slot)** — after `plugins.tools` seeds
   `:agent/tool-registry`, `plugins.secrets` wraps every effective `Tool`.
   Secret substitution is deny-by-default. A host tool receives plaintext
   only when its name and referenced labels are operator-allowlisted in
   `:capabilities`. Runtime-authored/promoted tools are marked
   `:untrusted-runtime` and always receive opaque handles, never plaintext.
   The plugin leaves a registry transform on the exchange context so
   same-exchange live-tool refreshes reapply the boundary.
2. **Redact (wrapped `invoke` + `:tools` sweep)** — tool result
   strings are scanned for every stored secret value and replaced with
   `[REDACTED:label]` before they can enter `:tool/results`, in-flight
   messages, the tool transcript, the response, or `:agent/state-delta`
   (i.e. history + memory persistence). The sweep remains a second layer
   for every static and live tool result.
3. **Read guard (file tools)** — the sealed store path segment
   `.lateralus` is in `file-safety/default-blocked-paths`, so
   `file_read`/`file_search` and the write tools cannot touch it.
4. **Labels are visible, values are not** — the plugin contributes two
   model-visible tools (opt-out `:advertise-handle-tool? false`):
   `secret_list_handles` returns labels only, and `secret_check` returns
   only whether an authorized handle resolved. There is no LLM-callable
   path that returns plaintext. The plugin also appends a system-guidance
   hint (via `act/merge-system-guidance`) telling the model the store
   exists, how the `{{secret:label}}` syntax works, and to never ask
   the user to paste a secret. Secrets are CREATED/DELETED only by the
   operator via the `kschltz.agent.secrets` API from a REPL:

```clojure
(require '[kschltz.agent.secrets :as secrets])
(def store (secrets/sealed-file-store {})) ; default path + passphrase env
(secrets/-put-secret! store "github-token" "ghp_...")
(secrets/-secret-labels store)
(secrets/-delete-secret! store "github-token")
```

## Storage

`sealed-file-store` — one AES-256-GCM sealed file (PBKDF2-HmacSHA256
master key, 210k iterations, per-file random salt, JDK-only). The
passphrase comes from the environment (`:passphrase-env`, default
`LATERALUS_SECRETS_PASSPHRASE`), never config text. A wrong passphrase
fails loudly on first decrypt (GCM auth).

## Integrant keys

- `:lateralus/secret-store` — `{:path "..." :passphrase-env "..."}`
  (impl `:sealed-file` is the only backend).
- `:lateralus/secret-plugin` — store, sandboxed factory session, disabled
  runtime registry, and explicit capabilities:

```clojure
{:store #ig/ref :lateralus/secret-store
 :factory-session #ig/ref :lateralus/factory-session
 :runtime-tools #ig/ref :lateralus/runtime-tools
 :capabilities
 {"secret_check" {:labels :all}
  "approved_mcp_tool" {:labels #{"github-token"}}}}
```

Opt-in: both keys are commented out in `resources/lateralus/config.edn`.
If enabled, place `:lateralus/secret-plugin` **after**
`:lateralus/tools-plugin` so the wrapping `:guard` interceptor sees the
seeded registry.

## Scale of the guarantee (read before trusting)

The plugin and factory form a capability boundary against model-authored
runtime code when the Integrant safety checks pass:

- Integrant refuses to initialize the secret plugin unless runtime eval is
  disabled and the factory uses its SCI sandbox. SCI code receives `nil`
  instead of the host context and has no classes, environment, filesystem,
  raw network, dynamic dependencies, requires, or runtime interceptors.
- A sandboxed runtime tool may invoke only operator-allowlisted host tool
  names through `lateralus.runtime/call-tool`. Results cross back as strings.
  Network-capable host tools must still use the protocol + Malli boundaries
  in `docs/network-boundaries.md`.
- Handle *selection* is model-visible: an injected prompt could ask the
  agent to call a legitimate handle-bound tool against an attacker's
  goal. Per-tool label capabilities constrain that confused-deputy surface;
  operators should grant the narrowest tool/label pairs.
- Redaction needles are literal values >= 8 chars (see
  `min-redact-length`). Redaction is defense-in-depth, not the boundary;
  encoded output is not assumed detectable. Needle sets are recomputed so
  store mutations apply immediately.
- The store still shares the agent JVM with trusted host code. A malicious
  dependency or compromised host implementation is outside this boundary.

## Managing secrets from the workbench UI

The workbench (CHAT | Portal UI) exposes a **Settings → Secrets**
section backed by `/api/secrets` (`kschltz.agent.workbench.secrets-http`):

- `GET /api/secrets` — `{:enabled bool, :labels [...]}`. Labels only;
  the API never serves a value back.
- `PUT /api/secrets` `{"session-id":"...","label":"...","value":"..."}`
  — same-origin, active-session upsert.
- `DELETE /api/secrets?label=...&session-id=...` — same-origin,
  active-session remove (idempotent).

Label rule: `^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$` (same accept-set as
the store). The UI clears the value field after save and shows the
handle (`{{secret:label}}`) to paste into tool arguments.

Wiring (opt-in): define `:lateralus/secret-store` +
`:lateralus/secret-plugin`, wire the same store into a sandboxed
`:lateralus/factory-session`, set `:lateralus/runtime-tools :enabled? false`,
and add `:secret-store #ig/ref :lateralus/secret-store` to
`:lateralus/workbench`.
Without the store the section is inert ("secret store not configured")
and both fields are disabled. The store is the SAME instance the secrets
plugin wraps tools with, so a value set in the UI is immediately
usable as `{{secret:label}}`.

## Tests

`clojure -M:test` — `kschltz.agent.secrets-test` (store roundtrip,
wrong passphrase, handle substitution, redaction, wrapped tool) and
`kschltz.agent.plugins.secrets-test` (slot placement, registry wrap,
sweep).