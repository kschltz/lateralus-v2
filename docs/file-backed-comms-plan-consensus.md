# Consensus Plan: Optional File-Backed Multi-Agent Communication Plugin

**Status: design consensus — not yet implemented.**

This document reconciles three parallel implementation plans for the `lateralus-v2` communication plugin. It is the proposal the implementation agent recommends the team adopt.

> **Older plans superseded.** Earlier drafts in `docs/comms-plugin-plan.md` and `docs/file-backed-comms-plan.md` are replaced by this consensus document.

## Scope

Add an optional, non-invasive plugin that lets multiple agent runtimes on the same machine discover, observe, and message each other through a shared file tree. When the plugin is not configured, the system behaves exactly as it does today.

## 1. Proposed File Tree Layout

Root is configurable under `:lateralus/comms :root`; default is `comms/` in the working directory.

```
comms/
├── registry.edn              # single source of truth for active agents
├── broadcasts/               # durable public messages to all agents
│   ├── 0001-<uuid>.edn
│   └── ...
└── agents/
    └── <agent-id>/
        ├── heartbeat.edn     # last heartbeat + status
        ├── profile.edn       # static metadata / capabilities / config
        ├── work.edn          # current task / exchange / focus
        └── inbox/
            ├── 0001-<uuid>.edn
            └── ...
```

Consensus decisions:
- `registry.edn` is the canonical registry. It is mutated by agents on join/leave and guarded by a process-level file lock or atomic rename. It is not append-only by default; read-modify-write is simpler for a single small file with up to 100 entries.
- Each agent owns its own directory. Heartbeat, profile, and work are overwritten atomically (write temp file + rename).
- Messages and broadcasts are durable files named with a monotonic sequence prefix and a UUID suffix so lexicographic order is delivery order and gaps are visible.
- All on-disk payloads are EDN maps validated against Malli schemas before write.
- Acknowledged inbox messages are moved to `agents/<id>/inbox/read/` instead of deleted, preserving durable history and allowing replay.

## 2. Protocols

Two protocols isolate the storage dependency and keep tests fast.

### 2.1 `AgentComms` — runtime contract

```clojure
(defprotocol AgentComms
  "Cross-agent communication and observation contract."
  (-agent-id       [comms] "Return this agent's stable identifier.")
  (-list-agents    [comms] "Return a vector of registry entries.")
  (-status         [comms agent-id] "Return status/heartbeat or nil.")
  (-profile        [comms agent-id] "Return profile or nil.")
  (-current-work   [comms agent-id] "Return current work or nil.")
  (-send           [comms opts] "Send a point-to-point message. opts: :to, :type, :payload. Returns message id.")
  (-broadcast      [comms opts] "Broadcast a message. opts: :type, :payload. Returns message id.")
  (-poll-inbox     [comms opts] "Return unread messages. opts: :since-seq, :limit.")
  (-poll-broadcasts [comms opts] "Return unread broadcasts. opts: :since-seq, :limit.")
  (-ack-message    [comms message-id] "Move message-id from inbox to inbox/read/."))
```

### 2.2 `CommsStorage` — storage backend contract

```clojure
(defprotocol CommsStorage
  "Abstracts the file tree so tests can swap in an in-memory backend."
  (-read-file   [storage path] "Return file contents as EDN, or nil if missing.")
  (-write-file  [storage path content] "Atomic write. Returns true on success.")
  (-list-files  [storage dir] "Return relative paths in directory, sorted.")
  (-move-file   [storage from to] "Atomic move. Returns true on success.")
  (-delete-file [storage path] "Return true on success.")
  (-with-lock   [storage path f] "Acquire a file lock, run f, release. Returns f's result."))
```

Rationale:
- `AgentComms` is the surface runtime and plugin code use. A future MCP backend can implement this same protocol.
- `CommsStorage` isolates the filesystem. The file implementation is the only namespace that performs I/O, satisfying the project rule that external dependencies be isolated behind protocols.
- All public functions in the file implementation are Malli-instrumented for input and output.

## 3. Key Namespaces and Responsibilities

| Namespace | Responsibility |
|-----------|----------------|
| `kschltz.agent.comms.protocol` | `AgentComms` and `CommsStorage` protocols. |
| `kschltz.agent.comms.schemas` | Malli schemas for all comms data structures and storage return values. |
| `kschltz.agent.comms.storage.file` | File-backed `CommsStorage` implementation; all I/O lives here; functions are instrumented. |
| `kschltz.agent.comms.file` | `AgentComms` implementation on top of any `CommsStorage`; registry logic, heartbeat publishing, message routing. |
| `kschltz.agent.comms.storage.memory` | In-memory `CommsStorage` for fast deterministic tests. |
| `kschltz.agent.comms.plugin` | Plugin factory producing interceptors in `:enrich`, `:observe`, and `:notify`. |
| `kschltz.agent.comms.runtime` | Thin helpers for setting busy/idle/current-work state via the protocol. |
| `kschltz.agent.system` | New `:lateralus/comms` Integrant key and plugin wiring. |

### Plugin slot assignment

The `:comms` plugin contributes three interceptors:

- `:enrich` — `comms/discover`
  - Read directory to add `:comms/agents`, `:comms/self`, and `:comms/unread` to the exchange context before `compose-context`.
  - Poll own inbox and broadcasts, surfacing unread messages.
- `:observe` — `comms/heartbeat`
  - Publish this agent's heartbeat and status (`:busy`) at leave while an exchange is in flight.
- `:notify` — `comms/send-and-idle`
  - Send any messages queued during the exchange via `AgentComms/-send` / `-broadcast`.
  - Update heartbeat back to `:idle`.
  - Finalize `work.edn`.

A background heartbeat refresher is out of scope for the first milestone; synchronous updates on chain enter/leave are sufficient for the MVP.

## 4. Integration with Integrant and the Plugin Interceptor Model

### 4.1 Integrant config

Add a single optional top-level key:

```clojure
{:lateralus/comms
 {:impl :file
  :root "comms"
  :agent-id "planner-1"
  :heartbeat-interval-ms 5000
  :profile {:role :planner :capabilities #{:plan :review}}}}
```

If `:lateralus/comms` is absent, the plugin is not loaded and the system is unchanged.

Add the plugin key:

```clojure
{:lateralus/comms-plugin {:comms (ig/ref :lateralus/comms)}}
```

And include it conditionally in `:lateralus/plugins`:

```clojure
{:lateralus/plugins {:plugins [#ig/ref :lateralus/memory-plugin
                               #ig/ref :lateralus/comms-plugin]}}
```

The default `default-config` in `kschltz.agent.system` does **not** include `:lateralus/comms` or `:lateralus/comms-plugin`, keeping tests unaffected.

### 4.2 `kschltz.agent.system` changes

- Add `CommsConfig` Malli schema.
- Add `ig/assert-key :lateralus/comms`.
- Add `ig/init-key :lateralus/comms` that dispatches on `:impl`:
  - `:file` builds a file-backed `CommsStorage` and wraps it in an `AgentComms` record.
  - `:noop` or absent returns `nil`.
- Add `ig/init-key :lateralus/comms-plugin` that calls `comms.plugin/comms-plugin` with the resolved `AgentComms`, or returns no plugin when comms is `nil`.
- Update `:lateralus/agent` to accept an optional `:comms` dependency and forward it in the agent-map under `:agent/comms`.

### 4.3 Interceptor model

The plugin follows the existing `kschltz.agent.plugin` contract:

```clojure
{:plugin/name :comms
 :plugin/slots
 {:enrich  [{:name ::discover :enter (fn [ctx] ...)}]
  :observe [{:name ::heartbeat :leave (fn [ctx] ...)}]
  :notify  [{:name ::send-and-idle :leave (fn [ctx] ...)}]}}
```

Interceptors close over the resolved `AgentComms`, mirroring how `plugins.memory` closes over `MemoryBackend`.

## 5. Malli Schemas

Defined in `kschltz.agent.comms.schemas`.

```clojure
(def AgentId
  "Filesystem-safe identifier."
  [:re "^[a-z0-9_-]+$"])

(def Timestamp
  "Unix epoch milliseconds."
  :int)

(def RegistryEntry
  "One row in registry.edn."
  [:map
   [:agent/id AgentId]
   [:agent/registered-at Timestamp]
   [:agent/last-seen Timestamp]
   [:agent/role {:optional true} :keyword]
   [:agent/capabilities {:optional true} [:set :keyword]]])

(def Registry
  "Top-level registry.edn payload."
  [:map
   [:registry/version [:= 1]]
   [:registry/updated-at Timestamp]
   [:registry/agents [:vector RegistryEntry]]])

(def Status
  "heartbeat.edn payload."
  [:multi {:dispatch :status/state}
   [:idle [:map
            [:status/state [:= :idle]]
            [:status/updated-at Timestamp]]]
   [:busy [:map
            [:status/state [:= :busy]]
            [:status/updated-at Timestamp]
            [:status/since Timestamp]
            [:status/exchange-id {:optional true} :string]
            [:status/session-id {:optional true} :string]]]])

(def Profile
  "profile.edn payload."
  [:map {:closed false}
   [:profile/agent-id AgentId]
   [:profile/role {:optional true} :keyword]
   [:profile/capabilities {:optional true} [:set :keyword]]
   [:profile/model {:optional true} :string]
   [:profile/description {:optional true} :string]
   [:profile/config {:optional true} :map]])

(def CurrentWork
  "work.edn payload."
  [:map
   [:work/agent-id AgentId]
   [:work/state [:enum :idle :working :waiting]]
   [:work/updated-at Timestamp]
   [:work/summary {:optional true} :string]
   [:work/started-at {:optional true} Timestamp]
   [:work/exchange-id {:optional true} :string]
   [:work/goal {:optional true} :string]
   [:work/tags {:optional true} [:vector :keyword]]])

(def MessagePriority
  [:enum :low :normal :high :urgent])

(def MessageEnvelope
  "A single message file (inbox or broadcast)."
  [:map
   [:msg/id :string]
   [:msg/from AgentId]
   [:msg/to {:optional true} AgentId]
   [:msg/sent-at Timestamp]
   [:msg/seq :int]
   [:msg/type :keyword]
   [:msg/priority {:optional true} MessagePriority]
   [:msg/payload [:map {:closed false}]]])

(def CommsConfig
  "Integrant config for :lateralus/comms."
  [:multi {:dispatch :impl}
   [:file [:map
           [:impl [:= :file]]
           [:root {:optional true} :string]
           [:agent-id AgentId]
           [:heartbeat-interval-ms {:optional true} :int]
           [:profile {:optional true} Profile]]]
   [:noop [:map [:impl [:= :noop]]]]])

(def StorageReadResult [:maybe :any])
(def StorageWriteResult [:enum true false])
(def StorageFileList [:sequential :string])
(def StorageMoveResult [:enum true false])
(def StorageDeleteResult [:enum true false])
```

Instrumentation:
- Public functions in `kschltz.agent.comms.storage.file` are wrapped with `malli.instrument/instrument!` against the schemas above.
- The `AgentComms` implementation in `kschltz.agent.comms.file` validates payloads before delegating to storage.

## 6. Implementation Milestones

1. **Scaffold & schemas**
   - Create `kschltz.agent.comms.schemas` with all schemas and self-check tests.

2. **Protocols & in-memory storage**
   - Define `AgentComms` and `CommsStorage` in `kschltz.agent.comms.protocol`.
   - Implement `kschltz.agent.comms.storage.memory`.
   - Write tests covering registry, status, profile, work, send, broadcast, poll, and ack without touching disk.

3. **File storage implementation**
   - Implement `kschltz.agent.comms.storage.file` with atomic writes, `FileChannel` locking, and Malli instrumentation.
   - Add concurrency tests for registry updates and atomic file replacement.

4. **Agent communication logic**
   - Implement `kschltz.agent.comms.file` on top of `CommsStorage`.
   - Add register/join/leave, heartbeat publishing, profile/work publishing, message routing.

5. **Integrant wiring**
   - Add `:lateralus/comms` and `:lateralus/comms-plugin` keys in `kschltz.agent.system`.
   - Forward `:comms` into the agent-map.

6. **Plugin interceptors**
   - Implement `kschltz.agent.comms.plugin`.
   - Add `kschltz.agent.comms.runtime` helpers.

7. **End-to-end tests**
   - Two Integrant systems sharing the same `comms/` root.
   - Discovery, status observation, point-to-point messaging, broadcast, and ack.

8. **Documentation**
   - Add `docs/file-backed-comms.md` and update README status.

## 7. Open Questions Resolved

| Topic | Consensus |
|-------|-----------|
| File format | EDN for all files. Readable by humans and by Clojure without extra deps. |
| Registry | `registry.edn`, read-modify-write under a file lock. Simpler than append-only JSONL for <=100 agents. |
| Per-agent files | `heartbeat.edn`, `profile.edn`, `work.edn`, `inbox/`, `inbox/read/`. |
| Broadcasts | Central `broadcasts/` directory with sequence-prefixed files. Each agent tracks its own high-water seq. |
| Ack semantics | Move inbox files to `inbox/read/` to preserve durable history. |
| Protocol split | `AgentComms` (runtime) + `CommsStorage` (backend). Keeps filesystem isolated and testable. |
| Plugin slots | `:enrich` for discovery/unread, `:observe` for heartbeat publish, `:notify` for queued sends + idle. |
| Agent id | Required in `:lateralus/comms :agent-id`. Must be unique per running process unless intentionally sharing an agent identity. CLI flag `--agent-id` is a follow-up convenience. |
| Runtime status accuracy | Because `runtime/send-message` is synchronous, `:observe` heartbeat/status runs only at exchange boundaries. "Busy" means "busy during the last completed exchange." Continuous busy/idle tracking requires a background heartbeat refresher (follow-up). |
| Default config | `default-config` does **not** include comms; plugin is opt-in. |

## 8. Dependencies and blockers

This plan is blocked on two in-progress architecture cards:

- **[009] Add Malli pre-init validation to Integrant components** — the new `:lateralus/comms` key will need its own `ig/assert-key` schema in `src/kschltz/agent/system.clj`, following the same pattern used for `:lateralus/llm-client`, `:lateralus/embedder`, and `:lateralus/memory-backend`. This card is currently in the `DONE` lane; its patterns are ready to reuse.
- **[007] Pre-wire dependencies into context instead of bind-llm-client** — the comms plugin will need to pass `:agent/comms` into the exchange context the same way the memory plugin passes `:memory/recall`. The current `bind-llm-client` indirection should be replaced by explicit pre-wiring before this plugin lands.

Both cards are tracked in `kb status`. This plan should not be implemented until card `[007]` is complete, or until a smaller non-context pre-wiring path is explicitly designed.

## 9. Code links

- Plugin slot order and assembly: `src/kschltz/agent/plugin.clj`
- Base plugin (default `:observe`/`:notify` slots): `src/kschltz/agent/plugins/base.clj`
- Memory plugin (pattern for `:enrich`/`:persist` pre-wiring): `src/kschltz/agent/plugins/memory.clj`
- Integrant config validation: `src/kschltz/agent/system.clj`
- Interceptor engine: `src/kschltz/agent/interceptors.clj`
- Context schema: `src/kschltz/agent/interceptors/schema.clj`

## 10. Remaining disagreements to resolve

1. **Registry lock strategy**
   - Consensus candidate: `java.nio.channels.FileChannel` lock on `registry.edn` during read-modify-write.
   - Alternative: atomic rename of a temp registry file. Needs discussion if lock files are considered risky on the target OS mix.

2. **Heartbeat staleness / garbage collection**
   - Consensus candidate: leave registry cleanup manual for the first milestone.
   - Alternative: automatically mark agents stale after a heartbeat timeout and prune on the next writer. This is useful but can be added later without breaking the file format.

3. **Message replay and read-folder cleanup**
   - Consensus candidate: move acked messages to `inbox/read/`.
   - Open: should there be a periodic compaction or size cap on `inbox/read/`?

4. **MCP compatibility**
   - Consensus candidate: keep `AgentComms` abstract; an MCP backend can implement it later.
   - Open: should we reserve a `:msg/mcp` payload type or keep the envelope agnostic?
