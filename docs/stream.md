# Live response streaming

The stream plugin records **thinking**, **token deltas**, and **response metadata**
while an exchange is running, then keeps the same snapshot after the turn ends.

## Pieces

| Piece | Role |
|---|---|
| `LlmClient` + `StreamableLlmClient` | HTTP client POSTs `stream: true` and parses SSE (`llm.http` / `llm.http-stream`) |
| `StreamBus` | In-process live + historic store (`:lateralus/stream-bus`) |
| `stream-plugin` | `:guard` opens a turn and wraps the LLM client; `:observe` finalizes |
| Workbench | Subtle `i` on assistant / in-flight turns → `/turn/<id>` in a new tab |

Non-streaming clients (stub, tests) still emit one assembled burst so the
details page has historic text/thinking/usage.

## Workbench UI

- Chat snapshot includes `:current-turn-id` while a turn is live.
- Completed assistant turns carry `:turn-id`.
- `GET /api/turns/<id>` — full snapshot (historic or in-progress).
- `GET /api/turns/<id>/events?since=N` — SSE of new events for the live page.

Clicking the info icon does not abort the turn; it only opens metadata.
