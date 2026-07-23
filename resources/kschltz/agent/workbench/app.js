(() => {
  const turnsEl = document.getElementById("turns");
  const statusEl = document.getElementById("status");
  const chipsEl = document.getElementById("chips");
  const inputEl = document.getElementById("input");
  const sendBtn = document.getElementById("send");
  const attachBtn = document.getElementById("attach");
  const portalFrame = document.getElementById("portal-frame");
  const portalLink = document.getElementById("portal-link");
  const portalReloadBtn = document.getElementById("portal-reload");
  const portalFallback = document.getElementById("portal-fallback");

  let attached = [];
  let lastRev = -1;
  let busy = false;
  let lastStatus = "connecting";
  let pollTimer = null;
  let stickToBottom = true;
  let lastPortalUrl = null;
  const STICK_THRESHOLD_PX = 64;

  function nearBottom() {
    const gap =
      turnsEl.scrollHeight - turnsEl.scrollTop - turnsEl.clientHeight;
    return gap <= STICK_THRESHOLD_PX;
  }

  function scrollTurnsToBottom() {
    turnsEl.scrollTop = turnsEl.scrollHeight;
  }

  turnsEl.addEventListener("scroll", () => {
    stickToBottom = nearBottom();
  }, { passive: true });

  function esc(s) {
    return String(s ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;");
  }

  function isBusy(status) {
    return status === "queued" || status === "running";
  }

  function setComposerEnabled(enabled) {
    sendBtn.disabled = !enabled;
    inputEl.disabled = !enabled;
    attachBtn.disabled = !enabled;
  }

  function renderStatus(status, detail) {
    const label = detail ? `${status} · ${detail}` : status;
    statusEl.textContent = label;
    statusEl.dataset.status = status || "idle";
    statusEl.title = label;
  }

  function ensureBusyPoller() {
    if (pollTimer) return;
    pollTimer = setInterval(() => {
      if (busy || isBusy(lastStatus)) {
        refresh();
      }
    }, 750);
  }

  function stopBusyPollerIfIdle() {
    if (!busy && !isBusy(lastStatus) && pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function bustPortalUrl(url) {
    if (!url) return url;
    const sep = url.includes("?") ? "&" : "?";
    return `${url}${sep}_wb=${Date.now()}`;
  }

  function setPortalUrl(url, { forceReload = false } = {}) {
    if (!url) {
      lastPortalUrl = null;
      portalFallback.classList.remove("hidden");
      return;
    }
    portalLink.href = url;
    portalFallback.classList.add("hidden");
    if (forceReload || lastPortalUrl !== url || !portalFrame.src) {
      portalFrame.src = bustPortalUrl(url);
      lastPortalUrl = url;
    }
  }

  function reloadPortal() {
    if (lastPortalUrl) setPortalUrl(lastPortalUrl, { forceReload: true });
  }

  function renderChips() {
    chipsEl.innerHTML = attached
      .map(
        (r, i) =>
          `<span class="chip" title="${esc(r.preview)}">@portal/${esc(
            String(r.id || "").slice(0, 8)
          )}${r.label ? " · " + esc(r.label) : ""}<button type="button" data-i="${i}" aria-label="Remove">×</button></span>`
      )
      .join("");
    chipsEl.querySelectorAll("button").forEach((btn) => {
      btn.addEventListener("click", () => {
        attached.splice(Number(btn.dataset.i), 1);
        renderChips();
      });
    });
  }

  function activityBubble(status, detail) {
    if (!isBusy(status)) return "";
    const copy =
      status === "queued"
        ? detail || "Queued — waiting for the session loop…"
        : detail || "Model working…";
    return `<article class="turn activity" aria-live="polite">
      <div class="role">assistant</div>
      <div class="activity-row">
        <span class="spinner" aria-hidden="true"></span>
        <span>${esc(copy)}</span>
      </div>
    </article>`;
  }

  function renderTurns(turns, status, detail) {
    const body = (turns || [])
      .map((t) => {
        const role = esc(t.role || "system");
        const refs = (t.refs || [])
          .map(
            (r) =>
              `<span class="chip">@portal/${esc(String(r.id || "").slice(0, 8))}</span>`
          )
          .join(" ");
        const thinking = t.thinking
          ? `<div class="thinking">${esc(t.thinking)}</div>`
          : "";
        return `<article class="turn ${role}"><div class="role">${role}</div>${esc(
          t.text || ""
        )}${refs ? `<div>${refs}</div>` : ""}${thinking}</article>`;
      })
      .join("");
    turnsEl.innerHTML = body + activityBubble(status, detail);
    if (stickToBottom) scrollTurnsToBottom();
  }

  function applyState(state) {
    if (!state) return;
    const nextStatus = state.status || "idle";
    const detail = state["status-detail"] || "";
    const rev = typeof state.rev === "number" ? state.rev : lastRev;
    // Drop stale snapshots — overlapping SSE/poll can reorder.
    if (lastRev !== -1 && rev < lastRev) return;
    if (lastRev !== -1 && rev === lastRev && nextStatus === lastStatus) return;

    const wasBusy = isBusy(lastStatus);
    lastRev = rev;
    lastStatus = nextStatus;
    busy = isBusy(lastStatus);
    renderStatus(lastStatus, detail);
    renderTurns(state.turns, lastStatus, detail);
    setComposerEnabled(!busy);
    if (busy) ensureBusyPoller();
    else stopBusyPollerIfIdle();

    const portalUrl = state["portal-url"];
    // After a turn finishes, force-reload iframe — recovers dead Portal websockets.
    const forceReload = wasBusy && !busy && !!portalUrl;
    setPortalUrl(portalUrl, { forceReload });
  }

  async function refresh() {
    try {
      const res = await fetch("/api/state", { cache: "no-store" });
      const state = await res.json();
      applyState(state);
    } catch (_) {
      renderStatus("offline", "cannot reach workbench");
      busy = false;
      setComposerEnabled(true);
      stopBusyPollerIfIdle();
    }
  }

  function connectEvents() {
    try {
      const es = new EventSource("/api/events?since=" + Math.max(lastRev, 0));
      es.onmessage = (ev) => {
        try {
          applyState(JSON.parse(ev.data));
        } catch (_) {}
      };
      es.onerror = () => {
        es.close();
        // SSE is best-effort; polling covers gaps while busy.
        refresh();
        setTimeout(connectEvents, 1500);
      };
    } catch (_) {
      ensureBusyPoller();
    }
  }

  async function send() {
    const text = inputEl.value.trim();
    if (!text || busy) return;
    busy = true;
    lastStatus = "queued";
    setComposerEnabled(false);
    renderStatus("queued", "sending…");
    ensureBusyPoller();
    stickToBottom = true;
    turnsEl.insertAdjacentHTML(
      "beforeend",
      activityBubble("queued", "sending…")
    );
    scrollTurnsToBottom();
    try {
      const res = await fetch("/api/message", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        cache: "no-store",
        body: JSON.stringify({ text, refs: attached }),
      });
      if (!res.ok) {
        const body = await res.text();
        throw new Error(body || "send failed");
      }
      inputEl.value = "";
      attached = [];
      renderChips();
      await refresh();
    } catch (e) {
      busy = false;
      lastStatus = "error";
      renderStatus("error", e.message || "send failed");
      turnsEl.insertAdjacentHTML(
        "beforeend",
        `<article class="turn error"><div class="role">error</div>${esc(
          e.message || "send failed"
        )}</article>`
      );
      if (stickToBottom) scrollTurnsToBottom();
      setComposerEnabled(true);
      stopBusyPollerIfIdle();
    } finally {
      inputEl.focus();
    }
  }

  async function attachSelection() {
    if (busy) return;
    try {
      const res = await fetch("/api/attach-selection", {
        method: "POST",
        cache: "no-store",
      });
      const body = await res.json();
      if (body.ok && body.ref) {
        attached.push(body.ref);
        renderChips();
      } else {
        renderStatus(lastStatus || "waiting", body.error || "no selection");
      }
    } catch (_) {
      renderStatus("error", "attach failed");
    }
  }

  sendBtn.addEventListener("click", send);
  attachBtn.addEventListener("click", attachSelection);
  portalReloadBtn.addEventListener("click", reloadPortal);
  inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      refresh();
      reloadPortal();
    }
  });

  // Always keep a slow heartbeat — SSE alone proved unreliable mid-turn.
  setInterval(refresh, 2500);

  refresh();
  connectEvents();
})();
