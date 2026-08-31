(() => {
  const turnId = decodeURIComponent(
    (window.location.pathname.split("/turn/")[1] || "").split("/")[0]
  );

  const $ = (id) => document.getElementById(id);

  function esc(s) {
    return String(s ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function isLive(snap) {
    if (!snap) return false;
    return snap["live?"] === true || snap.status === "live";
  }

  function setBadge(snap) {
    const el = $("badge");
    const live = isLive(snap);
    const err = snap.status === "error";
    el.textContent = err ? "error" : live ? "live" : "historic";
    el.className = "badge " + (err ? "error" : live ? "live" : "historic");
    document.title = (live ? "LIVE · " : "") + "response details";
  }

  function usageLine(usage) {
    if (!usage || typeof usage !== "object") return "—";
    const p = usage.prompt_tokens ?? usage["prompt-tokens"];
    const c = usage.completion_tokens ?? usage["completion-tokens"];
    const t = usage.total_tokens ?? usage["total-tokens"];
    if (p == null && c == null && t == null) return "—";
    return [p != null ? "prompt " + p : null, c != null ? "completion " + c : null, t != null ? "total " + t : null]
      .filter(Boolean)
      .join(" · ");
  }

  function timingLine(snap) {
    const open = snap["opened-at"];
    const close = snap["closed-at"];
    if (!open) return "—";
    const end = close || Date.now();
    return Math.max(0, end - open) + " ms";
  }

  function attachStick(el) {
    if (!el || el.dataset.stickBound) return;
    el.dataset.stickBound = "1";
    el.dataset.stick = "1";
    el.addEventListener("scroll", () => {
      const gap = el.scrollHeight - el.scrollTop - el.clientHeight;
      el.dataset.stick = gap < 48 ? "1" : "0";
    });
  }

  function pinBottom(el, force) {
    if (!el) return;
    attachStick(el);
    if (force || el.dataset.stick === "1") {
      el.scrollTop = el.scrollHeight;
    }
  }

  function prettyJson(raw) {
    if (raw == null) return "";
    const s = String(raw);
    try {
      return JSON.stringify(JSON.parse(s), null, 2);
    } catch (_) {
      return s;
    }
  }

  function renderToolResults(events) {
    const el = $("tool-results");
    if (!el) return;
    const hits = (events || []).filter(
      (e) => e.type === "tool-result" || e["tool-result"] != null
    );
    if (!hits.length) {
      el.innerHTML = '<p class="empty">—</p>';
      return;
    }
    el.innerHTML = hits
      .map((e) => {
        const name = e["tool-name"] || "tool";
        return `<article class="tool-result" data-tool="${esc(name)}"><h3>${esc(
          name
        )}</h3><pre>${esc(prettyJson(e["tool-result"]))}</pre></article>`;
      })
      .join("");
  }

  function render(snap, first) {
    if (!snap) return;
    $("title").textContent = snap.id || turnId || "response";
    $("prompt").textContent = snap["user-text"] || "";
    $("thinking").textContent = snap.thinking || "";
    $("response").textContent = snap.text || "";
    $("model").textContent = snap.model || "—";
    $("usage").textContent = usageLine(snap.usage);
    const tools = snap["tool-names"] || [];
    $("tools").textContent = tools.length ? tools.join(", ") : "—";
    $("timing").textContent = timingLine(snap);
    setBadge(snap);
    const events = snap.events || [];
    renderToolResults(events);
    $("events").innerHTML = events
      .map((e) => {
        const type = e.type || "";
        const extra =
          (e["tool-result"] != null
            ? (e["tool-name"] || "tool") + " → " + prettyJson(e["tool-result"])
            : null) ||
          e.text ||
          e.thinking ||
          e["tool-name"] ||
          e.error ||
          (e["elapsed-ms"] != null ? e["elapsed-ms"] + " ms" : "");
        return `<li><span>${e.seq ?? ""}</span><span class="type">${esc(
          type
        )}</span><span>${esc(extra)}</span></li>`;
      })
      .join("");
    requestAnimationFrame(() => {
      pinBottom($("thinking"), first);
      pinBottom($("response"), first);
      pinBottom($("events-wrap"), first);
    });
  }

  function mergeChunk(snap, chunk) {
    const next = Object.assign({}, snap);
    const incoming = chunk.events || [];
    const have = new Set((next.events || []).map((e) => e.seq));
    next.events = (next.events || []).concat(incoming.filter((e) => !have.has(e.seq)));
    incoming.forEach((e) => {
      if (e.text) next.text = (next.text || "") + e.text;
      if (e.thinking) next.thinking = (next.thinking || "") + e.thinking;
      if (e.model) next.model = e.model;
      if (e.usage) next.usage = e.usage;
      if (e["tool-name"] && e.type === "tool-call") {
        next["tool-names"] = next["tool-names"] || [];
        if (!next["tool-names"].includes(e["tool-name"])) {
          next["tool-names"].push(e["tool-name"]);
        }
      }
    });
    if (chunk.rev != null) next.rev = chunk.rev;
    if (chunk.status) next.status = chunk.status;
    if (chunk["live?"] === false || chunk.done) {
      next["live?"] = false;
      next.status = next.status === "error" ? "error" : "done";
    }
    return next;
  }

  async function fetchSnap(id) {
    const res = await fetch("/api/turns/" + encodeURIComponent(id), {
      cache: "no-store",
    });
    if (!res.ok) return null;
    return res.json();
  }

  async function resolveTurnId() {
    if (turnId && turnId !== "live") return turnId;
    for (let i = 0; i < 40; i++) {
      const res = await fetch("/api/turns/current", { cache: "no-store" });
      if (res.ok) {
        const snap = await res.json();
        if (snap && snap.id) return snap.id;
      }
      $("title").textContent = "waiting for a turn…";
      $("badge").textContent = "live";
      $("badge").className = "badge live";
      await new Promise((r) => setTimeout(r, 250));
    }
    return null;
  }

  async function load() {
    const id = await resolveTurnId();
    if (!id) {
      $("title").textContent = "no turn recorded yet";
      $("badge").textContent = "empty";
      return;
    }
    if (id !== turnId) {
      history.replaceState({}, "", "/turn/" + encodeURIComponent(id));
    }
    const snap0 = await fetchSnap(id);
    if (!snap0) {
      $("title").textContent = "turn not found";
      $("badge").textContent = "missing";
      $("prompt").textContent = "This URL has no stored metadata. Open details from CHAT after a reply.";
      return;
    }
    let snap = snap0;
    render(snap, true);
    if (!isLive(snap)) return;
    const last = (snap.events || []).reduce((n, e) => Math.max(n, e.seq ?? -1), -1);
    const es = new EventSource(
      "/api/turns/" + encodeURIComponent(id) + "/events?since=" + last
    );
    es.onmessage = (ev) => {
      try {
        const chunk = JSON.parse(ev.data);
        snap = mergeChunk(snap, chunk);
        render(snap);
        if (chunk.done || chunk["live?"] === false) es.close();
      } catch (_) {}
    };
  }

  load().catch((err) => {
    $("title").textContent = "failed to load";
    $("badge").textContent = String(err && err.message ? err.message : err);
  });
})();
