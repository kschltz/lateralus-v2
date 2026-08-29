# Workbench 2-way Portal loop

Artifacts the model renders via `portal_submit` can be **interactive**:
the human clicks/edits in the Portal iframe and the interaction lands in
the conversation as agent-visible input on the next exchange.

## Endpoint

`POST /api/portal-event` — Portal's iframe is same-origin with the
workbench HTTP server, so artifacts post with a **relative** fetch:

```js
function report(payload){
  fetch("/api/portal-event", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(payload)
  });
}
report({control: "vote", value: "yes"});
```

Body is either the event map itself or `{"payload": {...}}`.

Semantics (`hub/portal-event!`):

- Payload must be a JSON object (map); the serialized form is capped at
  4096 chars — oversized or non-map payloads get a 400, never a 500.
- Accepted events are published as a `:user` turn whose text begins
  with the `⟨portal-event⟩` marker **and** enqueued in the same inbox a
  chat message uses, so a session parked in `await-human!` wakes and
  the model sees it verbatim on the next exchange.
- The transcript is the event's sink too, so history/refresh keeps it.

## Trust model

Like chat input: the model authored the JS, but nothing is sent
without the human invoking a control. There is no code-carryback —
events are data only (contrast `portal.api/register!`, deliberately
not exposed). Never send secrets or raw file contents this way; events
join the persisted transcript.

## The full loop

1. `portal_submit` renders interactive HTML (buttons/sliders) with the
   `report()` helper (boilerplate in `guidance.clj`).
2. Human clicks a control → `POST /api/portal-event`.
3. `hub/portal-event!` publishes the turn + enqueue → session loop
   starts; the model sees `⟨portal-event⟩ {...}` as its next input.
4. Model reacts: `portal_submit` an updated artifact that *shows the
   new state*, closing the loop visibly.

For "human points at data" (no control was scripted), use
`portal_selected` instead — it reads the current Portal selection.

## Tests

`hub_test` covers transcript+inbox roundtrip and payload validation;
`http_test` covers the HTTP surface (200 / 400s).
