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

  /* ---------------------------------------------------------------------
   * Portal layout controller: split (docked, draggable divider) | float
   * (draggable/resizable window) | hidden. Persisted to localStorage.
   * On narrow viewports, a single-pane Chat ↔ Portal tab switcher takes
   * over; desktop float/split chrome is suppressed by CSS.
   * ------------------------------------------------------------------- */
  const shellEl = document.getElementById("shell");
  const dividerEl = document.getElementById("divider");
  const portalEl = document.getElementById("portal");
  const portalHead = document.getElementById("portal-head");
  const shieldEl = document.getElementById("drag-shield");
  const showPill = document.getElementById("portal-show");
  const mobileTabs = document.getElementById("mobile-tabs");
  const tabChat = document.getElementById("tab-chat");
  const tabPortal = document.getElementById("tab-portal");
  const rootStyle = document.documentElement.style;

  const LKEY = "lateralus.workbench.layout";
  const MKEY = "lateralus.workbench.mobilePane";
  const MIN_W = 320;
  const MIN_H = 220;
  const MIN_CHAT = 300;
  const MOBILE_MQ = window.matchMedia("(max-width: 900px)");

  const defaults = () => ({
    mode: "split",
    prev: "split",
    splitW: Math.round(window.innerWidth * 0.5),
    float: {
      x: Math.round(window.innerWidth * 0.45),
      y: Math.round(window.innerHeight * 0.12),
      w: 640,
      h: Math.round(window.innerHeight * 0.62),
    },
  });

  function loadLayout() {
    try {
      const saved = JSON.parse(localStorage.getItem(LKEY) || "null");
      if (!saved) return defaults();
      return Object.assign(defaults(), saved, {
        float: Object.assign(defaults().float, saved.float || {}),
      });
    } catch (_) {
      return defaults();
    }
  }

  function loadMobilePane() {
    try {
      const saved = localStorage.getItem(MKEY);
      return saved === "portal" ? "portal" : "chat";
    } catch (_) {
      return "chat";
    }
  }

  let layout = loadLayout();
  let mobilePane = loadMobilePane();

  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const isMobile = () => MOBILE_MQ.matches;

  function saveLayout() {
    try {
      localStorage.setItem(LKEY, JSON.stringify(layout));
    } catch (_) {}
  }

  function saveMobilePane() {
    try {
      localStorage.setItem(MKEY, mobilePane);
    } catch (_) {}
  }

  function clampFloat() {
    const f = layout.float;
    f.w = clamp(f.w, MIN_W, window.innerWidth);
    f.h = clamp(f.h, MIN_H, window.innerHeight);
    f.x = clamp(f.x, 0, Math.max(0, window.innerWidth - f.w));
    f.y = clamp(f.y, 0, Math.max(0, window.innerHeight - 40));
  }

  function syncMobileTabs() {
    const mobile = isMobile();
    if (mobileTabs) {
      if (mobile) mobileTabs.removeAttribute("hidden");
      else mobileTabs.setAttribute("hidden", "");
    }
    document.body.dataset.mobilePane = mobilePane;
    if (tabChat) tabChat.setAttribute("aria-selected", mobilePane === "chat" ? "true" : "false");
    if (tabPortal) tabPortal.setAttribute("aria-selected", mobilePane === "portal" ? "true" : "false");
  }

  function setMobilePane(pane) {
    mobilePane = pane === "portal" ? "portal" : "chat";
    saveMobilePane();
    syncMobileTabs();
    if (mobilePane === "chat" && stickToBottom) {
      requestAnimationFrame(scrollTurnsToBottom);
    }
  }

  function updateShellHeight() {
    // Keep the shell + tab bar inside the visual viewport so the soft
    // keyboard does not cover the composer on iOS/Android.
    const vv = window.visualViewport;
    const h = vv ? Math.round(vv.height) : window.innerHeight;
    rootStyle.setProperty("--shell-h", h + "px");
    if (!isMobile() || !vv) {
      if (mobileTabs) mobileTabs.style.bottom = "";
      if (shellEl) shellEl.style.marginTop = "";
      return;
    }
    const bottomGap = Math.max(0, window.innerHeight - (vv.offsetTop + vv.height));
    if (mobileTabs) mobileTabs.style.bottom = bottomGap + "px";
    if (shellEl) shellEl.style.marginTop = (vv.offsetTop || 0) + "px";
  }

  function applyLayout() {
    document.body.dataset.portalMode = layout.mode;
    const maxSplit = Math.max(MIN_W, window.innerWidth - MIN_CHAT);
    layout.splitW = clamp(layout.splitW, MIN_W, maxSplit);
    rootStyle.setProperty("--portal-w", layout.splitW + "px");
    clampFloat();
    rootStyle.setProperty("--pf-x", layout.float.x + "px");
    rootStyle.setProperty("--pf-y", layout.float.y + "px");
    rootStyle.setProperty("--pf-w", layout.float.w + "px");
    rootStyle.setProperty("--pf-h", layout.float.h + "px");
    syncMobileTabs();
    updateShellHeight();
    saveLayout();
  }

  function setMode(mode) {
    if (isMobile()) {
      // On mobile, hide/show map onto the Chat ↔ Portal tabs.
      if (mode === "hidden") {
        setMobilePane("chat");
        return;
      }
      setMobilePane("portal");
      // Keep a non-hidden desktop mode around for when the viewport widens.
      if (layout.mode === "hidden") {
        layout.mode = layout.prev && layout.prev !== "hidden" ? layout.prev : "split";
      }
      applyLayout();
      return;
    }
    if (mode !== "hidden" && layout.mode !== "hidden") layout.prev = layout.mode;
    if (mode === "hidden") layout.prev = layout.mode === "hidden" ? layout.prev : layout.mode;
    layout.mode = mode;
    applyLayout();
    // The iframe stays mounted across float/dock/hide, so a layout change must
    // NOT reload it (reloading flashes Portal's loader and drops render state).
    // Socket recovery is handled by the post-turn force-reload + manual reload.
  }

  function showPortal() {
    if (isMobile()) {
      setMobilePane("portal");
      return;
    }
    setMode(layout.prev && layout.prev !== "hidden" ? layout.prev : "split");
  }

  // Generic pointer-drag helper with an iframe shield + cursor override.
  function beginDrag(cursor, onMove, onEnd) {
    shieldEl.classList.add("active");
    shieldEl.style.cursor = cursor;
    const move = (ev) => onMove(ev);
    const up = (ev) => {
      document.removeEventListener("pointermove", move);
      document.removeEventListener("pointerup", up);
      shieldEl.classList.remove("active");
      shieldEl.style.cursor = "";
      if (onEnd) onEnd(ev);
      saveLayout();
    };
    document.addEventListener("pointermove", move);
    document.addEventListener("pointerup", up);
  }

  // Divider: resize the split columns.
  dividerEl.addEventListener("pointerdown", (e) => {
    if (isMobile() || layout.mode !== "split") return;
    e.preventDefault();
    dividerEl.classList.add("dragging");
    beginDrag(
      "col-resize",
      (ev) => {
        const w = window.innerWidth - ev.clientX;
        layout.splitW = clamp(w, MIN_W, window.innerWidth - MIN_CHAT);
        rootStyle.setProperty("--portal-w", Math.round(layout.splitW) + "px");
      },
      () => dividerEl.classList.remove("dragging")
    );
  });
  dividerEl.addEventListener("dblclick", () => {
    if (isMobile()) return;
    layout.splitW = Math.round(window.innerWidth * 0.5);
    applyLayout();
  });

  // Title-bar drag (float mode) — ignore clicks on the control buttons.
  portalHead.addEventListener("pointerdown", (e) => {
    if (isMobile() || layout.mode !== "float") return;
    if (e.target.closest("button, a")) return;
    e.preventDefault();
    const f = layout.float;
    const sx = e.clientX;
    const sy = e.clientY;
    const ox = f.x;
    const oy = f.y;
    beginDrag("move", (ev) => {
      f.x = clamp(ox + ev.clientX - sx, 0, window.innerWidth - f.w);
      f.y = clamp(oy + ev.clientY - sy, 0, window.innerHeight - 40);
      rootStyle.setProperty("--pf-x", Math.round(f.x) + "px");
      rootStyle.setProperty("--pf-y", Math.round(f.y) + "px");
    });
  });

  // Resize handles (float mode).
  portalEl.querySelectorAll(".rh").forEach((handle) => {
    handle.addEventListener("pointerdown", (e) => {
      if (isMobile() || layout.mode !== "float") return;
      e.preventDefault();
      e.stopPropagation();
      const dir = handle.dataset.dir;
      const f = layout.float;
      const sx = e.clientX;
      const sy = e.clientY;
      const o = { x: f.x, y: f.y, w: f.w, h: f.h };
      beginDrag(getComputedStyle(handle).cursor, (ev) => {
        const dx = ev.clientX - sx;
        const dy = ev.clientY - sy;
        if (dir.includes("e")) f.w = clamp(o.w + dx, MIN_W, window.innerWidth - o.x);
        if (dir.includes("s")) f.h = clamp(o.h + dy, MIN_H, window.innerHeight - o.y);
        if (dir.includes("w")) {
          const w = clamp(o.w - dx, MIN_W, o.x + o.w);
          f.x = o.x + (o.w - w);
          f.w = w;
        }
        if (dir.includes("n")) {
          const h = clamp(o.h - dy, MIN_H, o.y + o.h);
          f.y = o.y + (o.h - h);
          f.h = h;
        }
        rootStyle.setProperty("--pf-x", Math.round(f.x) + "px");
        rootStyle.setProperty("--pf-y", Math.round(f.y) + "px");
        rootStyle.setProperty("--pf-w", Math.round(f.w) + "px");
        rootStyle.setProperty("--pf-h", Math.round(f.h) + "px");
      });
    });
  });

  // Window-control buttons.
  const on = (id, fn) => {
    const el = document.getElementById(id);
    if (el) el.addEventListener("click", fn);
  };
  on("portal-float", () => setMode("float"));
  on("portal-dock", () => setMode("split"));
  on("portal-hide", () => setMode("hidden"));
  on("portal-show", showPortal);
  if (showPill) showPill.addEventListener("click", showPortal);
  on("portal-snap-left", () => {
    layout.float = { x: 0, y: 0, w: Math.round(window.innerWidth / 2), h: window.innerHeight };
    applyLayout();
  });
  on("portal-snap-right", () => {
    const w = Math.round(window.innerWidth / 2);
    layout.float = { x: window.innerWidth - w, y: 0, w, h: window.innerHeight };
    applyLayout();
  });
  on("portal-max", () => {
    layout.float = { x: 0, y: 0, w: window.innerWidth, h: window.innerHeight };
    applyLayout();
  });

  if (tabChat) tabChat.addEventListener("click", () => setMobilePane("chat"));
  if (tabPortal) tabPortal.addEventListener("click", () => setMobilePane("portal"));

  document.addEventListener("keydown", (e) => {
    const mod = e.metaKey || e.ctrlKey;
    if (mod && (e.key === "j" || e.key === "J")) {
      e.preventDefault();
      if (isMobile()) {
        setMobilePane(mobilePane === "portal" ? "chat" : "portal");
      } else {
        layout.mode === "hidden" ? showPortal() : setMode("hidden");
      }
    } else if (e.key === "Escape" && layout.mode === "float") {
      setMode("hidden");
    }
  });

  const onMq = () => applyLayout();
  if (typeof MOBILE_MQ.addEventListener === "function") {
    MOBILE_MQ.addEventListener("change", onMq);
  } else if (typeof MOBILE_MQ.addListener === "function") {
    MOBILE_MQ.addListener(onMq);
  }

  window.addEventListener("resize", applyLayout);
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", updateShellHeight);
    window.visualViewport.addEventListener("scroll", updateShellHeight);
  }
  applyLayout();

  // Always keep a slow heartbeat — SSE alone proved unreliable mid-turn.
  setInterval(refresh, 2500);

  refresh();
  connectEvents();
})();
