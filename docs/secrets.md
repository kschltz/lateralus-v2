# Secrets plugin (`kschltz.agent.secrets`)

Hold secrets in the agent process so **LLM tools can use them but the
model can never read them**.

## Design: use-without-seeing

The model never holds a secret value. It references secrets by
**handle**: `{{secret:label}}` inside tool arguments. Resolution and
redaction happen inside the tool boundary:

1. **Wrap (`:guard` slot)** — after `plugins.tools` seeds
   `:agent/tool-registry`, `plugins.secrets` wraps every static
   `Tool`: model-supplied handles resolve to plaintext at
   `:tool/-invoke` time only (substituted args never land on the ctx,
   only inside the delegate invocation).
2. **Redact (wrapped `invoke` + `:tools` sweep)** — tool result
   strings are scanned for every stored secret value and replaced with
   `[REDACTED:label]` before they can enter `:tool/results`, in-flight
   messages, the tool transcript, the response, or `:agent/state-delta`
   (i.e. history + memory persistence). The sweep also covers live
   MCP/factory tools the wrapper cannot reach.
3. **Read guard (file tools)** — the sealed store path segment
   `.lateralus` is in `file-safety/default-blocked-paths`, so
   `file_read`/`file_search` and the write tools cannot touch it.
4. **Labels are visible, values are not** — the plugin contributes
   exactly ONE model-visible tool (opt-out `:advertise-handle-tool?
   false`): `secret_list_handles`, which returns the secret LABELS as
   `{:handles [...]}` — never values. There is no LLM-callable path
   that returns plaintext. The plugin also appends a system-guidance
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
- `:lateralus/secret-plugin` — `{:store #ig/ref :lateralus/secret-store}`

Opt-in: both keys are commented out in `resources/lateralus/config.edn`.
If enabled, place `:lateralus/secret-plugin` **after**
`:lateralus/tools-plugin` so the wrapping `:guard` interceptor sees the
seeded registry.

## Scale of the guarantee (read before trusting)

This is a **best-effort defense against context exfiltration, not a
security boundary**:

- The store shares the agent JVM. An in-process adversary
  (e.g. unscoped `clojure_eval`) can reach the store. Keep
  `tools.runtime` disabled/sandboxed when real secrets are in play.
- Handle *selection* is model-visible: an injected prompt could ask the
  agent to call a legitimate handle-bound tool against an attacker's
  goal (confused deputy). Handle scoping/allowlists belong to your tool
  configuration, not to this plugin.
- Redaction needles are literal values >= 8 chars (see
  `min-redact-length`); tiny secrets still can't leak through handles,
  but they are not used as redaction needles to avoid mangling output.
- The plugin's redact needle set is computed once per plugin instance;
  rebuild (runtime reload) after mutating the store to refresh it.

## Managing secrets from the workbench UI

The workbench (CHAT | Portal UI) exposes a **Settings → Secrets**
section backed by `/api/secrets` (`kschltz.agent.workbench.secrets-http`):

- `GET /api/secrets` — `{:enabled bool, :labels [...]}`. Labels only;
  the API never serves a value back.
- `PUT /api/secrets` `{"label": "...", "value": "..."}` — upsert.
- `DELETE /api/secrets?label=...` — remove (idempotent).

Label rule: `^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$` (same accept-set as
the store). The UI clears the value field after save and shows the
handle (`{{secret:label}}`) to paste into tool arguments.

Wiring (opt-in): define `:lateralus/secret-store` + `:lateralus/secret-plugin`
and add `:secret-store #ig/ref :lateralus/secret-store` to
`:lateralus/workbench` (WorkbenchConfig gains an optional `:secret-store`).
Without the store the section is inert ("secret store not configured")
and both fields are disabled. The store is the SAME instance the secrets
plugin wraps tools with, so a value set in the UI is immediately
usable as `{{secret:label}}`.

## Tests

`clojure -M:test` — `kschltz.agent.secrets-test` (store roundtrip,
wrong passphrase, handle substitution, redaction, wrapped tool) and
`kschltz.agent.plugins.secrets-test` (slot placement, registry wrap,
sweep).