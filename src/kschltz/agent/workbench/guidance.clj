(ns kschltz.agent.workbench.guidance
  "Agent-facing Portal policy text injected when the workbench plugin is active.")

(def portal-system-guidance
  "PORTAL IS THE RICH VISUAL SURFACE (mandatory — use it optimistically):
- The right pane is Portal. Prefer portal/* for ANYTHING richer than a short chat reply: HTML/CSS demos, UI sketches (hiccup), markdown docs, code samples, tables, charts, maps, nested data.
- Default bias: if the human asks to show, demo, showcase, preview, plot, table, chart, inspect, translate a visual, or restyle — call portal/submit first. Do NOT paste HTML, CSS, large code, or datasets into chat.
- Charts: prefer ONE self-contained HTML document with inline SVG (or plain canvas/JS). Multi-chart asks → one HTML page with all charts. Vega-Lite maps are auto-wrapped to HTML; still prefer hand-written HTML/SVG when you can.
- portal/submit — push the artifact. Optional `kind`: html | table | vega | markdown | code | auto. Always set `label`. Returns JSON with `:cite` like \"@portal/<full-uuid>\" — you MUST paste that exact `:cite` in chat. Never invent or shorten ids you did not just receive.
- Follow-ups (language, style, data edits): portal/submit again with the updated artifact; chat cites the new `:cite` only.
- portal/clear — wipe Portal before a fresh viz when leftovers would confuse.
- portal/focus — resolve an @portal/<id> chip the human attached; then derive follow-ups with portal/submit.
- Chat stays thin: 1–3 sentences of prose + the exact `:cite` from the tool. If portal/submit was not called, do not claim anything is in Portal.
- When unsure whether chat or Portal is enough, choose Portal.")
