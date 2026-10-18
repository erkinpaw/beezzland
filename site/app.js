(() => {
  const cfg = window.BEEZZ || {};
  const $$ = (s) => document.querySelectorAll(s);

  $$("[data-ip]").forEach((el) => (el.textContent = cfg.ip || "localhost"));
  $$("[data-year]").forEach((el) => (el.textContent = new Date().getFullYear()));

  // «Как зайти»: Java / Bedrock tabs, only when phones can join
  const tabs = document.querySelector("[data-join-tabs]");
  if (tabs && cfg.bedrock) {
    tabs.hidden = false;
    tabs.addEventListener("click", (e) => {
      const key = e.target.dataset && e.target.dataset.tab;
      if (!key) return;
      $$("[data-tab]").forEach((b) => b.classList.toggle("on", b.dataset.tab === key));
      $$("[data-panel]").forEach((p) => (p.hidden = p.dataset.panel !== key));
    });
  }

  const names = { vk: "ВКонтакте", telegram: "Telegram", discord: "Discord", youtube: "YouTube" };
  const socials = document.querySelector("[data-socials]");
  Object.entries(cfg.links || {}).forEach(([k, url]) => {
    if (!url) return;
    const a = document.createElement("a");
    a.href = url; a.target = "_blank"; a.rel = "noopener"; a.textContent = names[k] || k;
    socials.append(a);
  });

  const toast = document.querySelector("[data-toast]");
  document.querySelector("[data-copy]")?.addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(cfg.ip); } catch { /* clipboard blocked: still show hint */ }
    toast.classList.add("show");
    setTimeout(() => toast.classList.remove("show"), 1600);
  });

  const set = (sel, v) => $$(sel).forEach((el) => (el.textContent = v));
  async function refresh() {
    const dot = document.querySelector("[data-dot]");
    try {
      const s = await (await fetch("/api/status", { cache: "no-store" })).json();
      if (!s.online) throw 0;
      dot.className = "dot on";
      set("[data-state]", "онлайн");
      set("[data-players]", `${s.players.online ?? 0} / ${s.players.max ?? "—"}`);
      set("[data-ping]", `${s.latency_ms} мс`);
    } catch {
      dot.className = "dot off";
      set("[data-state]", "офлайн");
      set("[data-players]", "—");
      set("[data-ping]", "—");
    }
  }
  refresh();
  setInterval(refresh, 15000);
})();
