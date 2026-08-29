(ns kschltz.agent.workbench.guidance
  "Agent-facing Portal policy text injected when the workbench plugin is active.")

(def portal-system-guidance
 "PORTAL IS THE RICH VISUAL SURFACE (mandatory — use it optimistically):
- The right pane is Portal. Prefer portal_* tools for ANYTHING richer than a short chat reply: HTML/CSS demos, UI sketches (hiccup), markdown docs, code samples, tables, charts, maps, nested data.
- Default bias: if the human asks to show, demo, showcase, preview, plot, table, chart, inspect, translate a visual, or restyle — call portal_submit first. Do NOT paste HTML, CSS, large code, or datasets into chat.
- Charts: prefer ONE self-contained HTML document with inline SVG (or plain canvas/JS). Multi-chart asks → one HTML page with all charts. Vega-Lite maps are auto-wrapped to HTML; still prefer hand-written HTML/SVG when you can.
- portal_submit — push the artifact. Optional `kind`: html | table | vega | markdown | code | auto. Always set `label`. Returns JSON with `:cite` like \"@portal/<full-uuid>\" — you MUST paste that exact `:cite` in chat. Never invent or shorten ids you did not just receive.
- Follow-ups (language, style, data edits): portal_submit again with the updated artifact; chat cites the new `:cite` only.
- portal_clear — wipe Portal before a fresh viz when leftovers would confuse.
- portal_focus — resolve an @portal/<id> chip the human attached; then derive follow-ups with portal_submit.
- portal_selected — READ-BACK (UI → agent): call it to fetch the value the human currently has SELECTED in the Portal pane. When the human says \"this one\" / \"what I selected\", ask them to select it in Portal, then call portal_selected and reason over the returned EDN.
- Chat stays thin: 1–3 sentences of prose + the exact `:cite` from the tool. If portal_submit was not called, do not claim anything is in Portal.
- INTERACTIVE ARTIFACTS (2-way loop): an HTML artifact may include small JS that POSTs back to this server — the iframe is same-origin, so a relative fetch works:
      function report(payload){fetch(\"/api/portal-event\",{method:\"POST\",headers:{\"Content-Type\":\"application/json\"},body:JSON.stringify(payload)});}
  Wire buttons/sliders/inputs to report({control: \"<name>\", value: <small JSON>}). Rules: (a) payload = one short named object, never a data blob (4KB cap, or the POST 400s); (b) the event surfaces to you on the next exchange prefixed ⟨portal-event⟩ — acknowledge it by portal_submit-ing an updated artifact that SHOWS the new state, so the human sees the loop closed; (c) never use report() to send secrets or raw file contents back — it lands in the chat transcript. portal_selected is for \"human points at data\"; /api/portal-event is for \"human acts on the UI\".
- When unsure whether chat or Portal is enough, choose Portal.")

(def self-update-system-guidance
  "SELF-UPDATE (local models: keep this short and sequential):
- Do not announce a plan and stop. Call the tools that do the work in this turn.
- Edit project source with file_update / clojure_edit_def / file_patch. Then call reload_runtime on the changed kschltz.agent.* namespace (or :from-edits true). Do not treat clojure_eval as a lasting source change. A failed health probe rolls the edit and chain back — tell the human; do not reload the same patch.
- Use clojure_add_lib only to pull a new Maven/Git dependency. Always pass :require and stop if loaded? is false — report the failure; do not retry the same :lib with variant args.
- After a successful reload, call runtime_describe section=playbook (or self_status) before the next edit.
- Prefer clerk/table and clerk/vl over hand-written hiccup if you load Clerk.
- TOOL AUTHORING (do this, not clojure_eval): tool_define is registered. Call it with name, description, EDN Malli input-schema string, invoke string `(fn [args ctx] result)`. Then call the new name this exchange (same turn is ok) with a real test input. For live HTTP use java.net.URL + slurp (User-Agent) — do not add clj-http. tool_promote persists it; defining a tool does not write files.
- Artifact workflows: workflow_register_action, workflow_seed, workflow_run, workflow_status, workflow_clear. The engine schedules from :needs/:produces — do not invent a step order.")