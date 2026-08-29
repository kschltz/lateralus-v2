# Skill packs (`kschltz.agent.skills`)

Progressive disclosure of expert knowledge: the model always sees only a
catalog of **selectors** (name + description) and loads full
instructions **on demand** as a tool result.

## What a skill is

One pure-data `.edn` file per skill in the store directory. The shape
is a strict Malli schema (`skills/SkillSchema`, closed map), validated
at startup — one invalid file fails the boot loudly:

```clojure
{:name        "deploy-runbook"     ; ^[a-z][a-z0-9-]{0,63}$
 :description "Deploy steps. Use when deploying."   ; selector, <=1024
 :body        "1. ...\n2. ..."      ; Tier 2 — only via load_skill
 :resources   [{:path "references/env.md"          ; Tier 3 — only via
               :description "env var matrix"}]}    ; read_skill_file
```

- `:resources` is optional; paths are relative, `..` rejected at load,
  and at read time confined by canonicalization inside the skill's own
  directory.
- The store is `:lateralus/skills-store {:path "skills"}` (Integrant,
  `skills/load-skills-dir`); declare
  `:lateralus/skills-plugin {:store #ig/ref :lateralus/skills-store}`
  and add it to `:lateralus/plugins` **after** `:lateralus/secret-plugin`
  if both are enabled (ordering keeps secrets wrapping ahead).
- Both keys are commented out in `resources/lateralus/config.edn`
  (opt-in, consistent with the secrets plugin).

## The three tiers

| Tier | Content | Lives in |
|------|---------|----------|
| 1 | `name` + `description` (selector) | system prompt catalog fragment (byte-stable, sorted — cache friendly) |
| 2 | `:body` | `load_skill` tool result (conversation-scoped; history trimmers can retire it) |
| 3 | `:resources` files | `read_skill_file` tool result |

Model surface contributions by the plugin:

- `load_skill(name)` — Tier 2 disclosure; lists declared resources;
  unknown names get a model-visible error with the available names.
- `read_skill_file(skill, path)` — Tier 3; path must be one of the
  skill's declared `:resources`. Un-declared and escaping paths are
  refused without touching the filesystem.

The plugin appends a system guidance line telling the model a skill
pack is NOT a tool and to load proactively when a task matches — the
catalog is the trigger surface, so keep descriptions as selectors
("what + use when…"), never workflow summaries (Anthropic's finding:
models then follow the description instead of loading the body).

## Measured property

`kschltz.agent.skills-test/system-prompt-is-smaller-with-skills-plugin`
asserts the required invariant: a fully-speced system prompt with the
skill knowledge inlined (the pre-plugin composition) is strictly LARGER
than the post-plugin composition (catalog only), by thousands of chars,
while the catalog still accounts for every skill. Bodies exist only in
tool results.

## Design constraints encoded

- Flat, one level of routing (LoongDoc arXiv 2607.17598: deeper routing
  packs never helped and sometimes broke accuracy).
- Catalog is deterministic and never carries per-skill loaded-state
  (keeps the prompt-caching prefix stable).
- Skills are pure data (`.edn`), not code; scripts-in-skills is a
  natural follow-up via the agent's existing shell/clojure tools
  (stdout enters context, script text doesn't).

## Tests

`clojure -M:test` — `kschltz.agent.skills-test` (schema, dir loading,
catalog, tools, containment, prompt-size invariant) and
`kschltz.agent.plugins.skills-test` (slot placement, registry, wiring).
