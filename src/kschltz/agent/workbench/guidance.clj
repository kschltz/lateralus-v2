(ns kschltz.agent.workbench.guidance
  "Agent-facing Portal policy text injected when the workbench plugin is active.")

(def portal-system-guidance
  "PORTAL IS THE RICH VISUAL SURFACE (mandatory — use it optimistically):
- The right pane is Portal. Prefer portal/* for ANYTHING richer than a short chat reply: HTML/CSS demos, UI sketches (hiccup), markdown docs, code samples, tables, charts (vega-lite), maps, nested data, technique indexes.
- Default bias: if the human asks to show, demo, showcase, preview, plot, table, inspect, or present — call portal/submit first. Do NOT paste HTML, CSS, large code, or datasets into chat.
- portal/submit — push the artifact into Portal. Prefer native shapes: HTML document/string for live preview, hiccup vectors for UI, markdown string for docs, JSON array-of-maps for tables, vega-lite map for charts. JSON may arrive as a string; still submit it (host coerces). Always set a clear `label`.
- portal/clear — wipe Portal before a fresh viz when leftovers would confuse.
- portal/focus — resolve an @portal/<id> chip the human attached; then derive follow-ups with portal/submit.
- Chat stays thin: 1–3 sentences of prose + cite @portal/<id>. Portal holds the visual the human sees on the right.
- When unsure whether chat or Portal is enough, choose Portal.")
