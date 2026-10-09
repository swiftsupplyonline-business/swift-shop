// SwiftShop Web – shared UI primitives for the server-rendered share pages and the SPA.
// Pure presentation (no Firebase). Used by listing-detail.js and shop-detail.js.

export const esc = (s) => String(s ?? "")
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
  .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

// Only http(s) URLs may reach src/href/background attributes.
export const safeUrl = (u) => (/^https?:\/\//i.test(String(u || "")) ? String(u) : "");

/**
 * Horizontal scrolling rail: title, optional subtitle / "See all", prev-next controls (hover devices),
 * edge fades, scroll-snap. `id` must be unique on the page (used for aria-labelledby).
 * Returns "" when there is nothing to show so callers can drop it in unconditionally.
 */
export function railHTML({ id, title, subtitle = "", items = [], renderItem, seeAllHref = "", seeAllLabel = "See all" }) {
  if (!items.length || typeof renderItem !== "function") return "";
  const safeId = String(id || title).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  const all = seeAllHref
    ? `<a class="rl-all" href="${esc(seeAllHref)}">${esc(seeAllLabel)} <span aria-hidden="true">›</span></a>` : "";
  return `
  <section class="rl" data-rail aria-labelledby="rl-${esc(safeId)}">
    <header class="rl-head">
      <div class="rl-titles">
        <h2 class="rl-title" id="rl-${esc(safeId)}">${esc(title)}</h2>
        ${subtitle ? `<p class="rl-sub">${esc(subtitle)}</p>` : ""}
      </div>
      <div class="rl-ctrl">
        ${all}
        <button type="button" class="rl-btn" data-rl-prev aria-label="Scroll ${esc(title)} left" disabled>‹</button>
        <button type="button" class="rl-btn" data-rl-next aria-label="Scroll ${esc(title)} right">›</button>
      </div>
    </header>
    <div class="rl-viewport">
      <div class="rl-track" data-rl-track tabindex="0" role="list" aria-label="${esc(title)}">
        ${items.map((item) => `<div class="rl-item" role="listitem">${renderItem(item)}</div>`).join("")}
      </div>
    </div>
  </section>`;
}

/** Wires every rail inside `root`: arrow buttons, keyboard, and scroll-position state for fades/disabled arrows. */
export function bindRails(root) {
  root.querySelectorAll("[data-rail]").forEach((rail) => {
    const track = rail.querySelector("[data-rl-track]");
    if (!track || rail.dataset.railBound === "1") return;
    rail.dataset.railBound = "1";
    const prev = rail.querySelector("[data-rl-prev]");
    const next = rail.querySelector("[data-rl-next]");
    const step = () => Math.max(160, Math.round(track.clientWidth * 0.85));
    const update = () => {
      const max = Math.max(0, track.scrollWidth - track.clientWidth);
      const x = track.scrollLeft;
      rail.dataset.scrollable = max > 4 ? "true" : "false";
      rail.dataset.atStart = x <= 2 ? "true" : "false";
      rail.dataset.atEnd = x >= max - 2 ? "true" : "false";
      if (prev) prev.disabled = x <= 2;
      if (next) next.disabled = x >= max - 2;
    };
    const by = (dx) => (track.scrollBy ? track.scrollBy({ left: dx, behavior: "smooth" }) : (track.scrollLeft += dx));
    prev?.addEventListener("click", () => by(-step()));
    next?.addEventListener("click", () => by(step()));
    track.addEventListener("scroll", update, { passive: true });
    track.addEventListener("keydown", (e) => {
      if (e.key === "ArrowRight") { e.preventDefault(); by(step()); }
      if (e.key === "ArrowLeft") { e.preventDefault(); by(-step()); }
    });
    if (typeof ResizeObserver !== "undefined") new ResizeObserver(update).observe(track);
    else window.addEventListener("resize", update);
    update();
  });
}
