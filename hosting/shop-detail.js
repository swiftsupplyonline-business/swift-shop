// SwiftShop Web – Shop Detail view (parity with Android ShopDetailScreen, plus discovery rails).
//
// Pure presentation: no Firebase imports and no commerce logic. app.js injects the data and the product
// card renderer; the SPA (/shop/:id click-through) and the server-rendered /shop/:id share page mount the
// same view. Follow / message need a signed-in Swift account, which the Web does not have yet, so they are
// pointed at the app instead of shown as dead buttons.

import { esc, safeUrl, railHTML, bindRails } from "./ui-kit.js";
import { ctaFor, shopCoords, directionsUrl, APP_LINKS } from "./listing-detail.js";

export const SORTS = {
  newest: "Newest first",
  popular: "Most popular",
  "price-asc": "Price: low to high",
  "price-desc": "Price: high to low"
};

const engagement = (l) =>
  (Number(l.commitmentCount) || 0) + (Number(l.likeCount) || 0) + (Number(l.bookmarkCount) || 0) + (Number(l.commentCount) || 0);

export function shopWhatsappUrl(shop) {
  const digits = String(shop?.whatsappNumber || "").replace(/\D/g, "");
  if (digits.length < 7) return "";
  return `https://wa.me/${digits}?text=${encodeURIComponent(`Hi ${shop.name || ""}, I found your shop on Swift.`.replace(/\s+,/, ","))}`;
}

export function deepLinkForShop(shopId) {
  return `swiftshop://shop/${encodeURIComponent(shopId)}`;
}

/** Filter chips = distinct listing types with counts (Android: "All (n)", "Buy Now (n)", …). */
export function listingTypes(listings) {
  const counts = new Map();
  for (const l of listings) {
    const t = String(l.listingType || "BUY");
    counts.set(t, (counts.get(t) || 0) + 1);
  }
  return [...counts.entries()]
    .map(([type, count]) => ({ type, count, label: ctaFor(type).label }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
}

export function filterListings(listings, { q = "", type = "ALL", sort = "newest" } = {}) {
  const needle = String(q).trim().toLowerCase();
  let out = listings.filter((l) => {
    if (type !== "ALL" && String(l.listingType || "BUY") !== type) return false;
    if (!needle) return true;
    const hay = [l.title, l.description, l.category, ...(Array.isArray(l.tags) ? l.tags : [])].join(" ").toLowerCase();
    return hay.includes(needle);
  });
  const price = (l) => Number(l.priceMinorUnits) || 0;
  const created = (l) => Number(l.createdAt) || 0;
  out = [...out];
  if (sort === "price-asc") out.sort((a, b) => price(a) - price(b));
  else if (sort === "price-desc") out.sort((a, b) => price(b) - price(a));
  else if (sort === "popular") out.sort((a, b) => engagement(b) - engagement(a) || created(b) - created(a));
  else out.sort((a, b) => created(b) - created(a));
  return out;
}

/** Rails are only worth showing when they add something the grid does not, and must not repeat each other. */
export function deriveRails(listings) {
  const popular = listings.length >= 4
    ? [...listings].filter((l) => engagement(l) > 0).sort((a, b) => engagement(b) - engagement(a)).slice(0, 10) : [];
  const seen = new Set(popular.map((l) => l.id));
  const fresh = listings.length >= 6
    ? [...listings].filter((l) => Number(l.createdAt) > 0 && !seen.has(l.id)).sort((a, b) => b.createdAt - a.createdAt).slice(0, 10) : [];
  return { popular, newest: fresh.length >= 3 ? fresh : [] };
}

const compact = (n) => {
  const v = Number(n) || 0;
  return v >= 1000 ? `${(v / 1000).toFixed(v >= 10000 ? 0 : 1).replace(/\.0$/, "")}k` : String(v);
};

function otherShopChip(s) {
  const logo = safeUrl(s.logoUrl);
  const initial = esc(String(s.name || "S").trim().charAt(0).toUpperCase() || "S");
  return `
    <a class="sd-shopchip" href="/shop/${encodeURIComponent(s.id)}">
      ${logo ? `<img class="sd-sc-logo" src="${esc(logo)}" alt="" loading="lazy">` : `<span class="sd-sc-logo sd-sc-fallback" aria-hidden="true">${initial}</span>`}
      <span class="sd-sc-text">
        <span class="sd-sc-name">${esc(s.name || "Shop")}${s.isVerified ? ` <span class="sd-verified" aria-label="Verified shop">✔</span>` : ""}</span>
        <span class="sd-sc-cat">${esc(s.category || "Local shop")}</span>
      </span>
    </a>`;
}

export function renderShopDetailHTML(ctx) {
  const { shop, listings = [], otherShops = [], cardHTML, standalone = false } = ctx;
  const name = shop.name || "Shop";
  const cover = safeUrl(shop.coverUrl);
  const logo = safeUrl(shop.logoUrl);
  const coords = shopCoords(shop);
  const wa = shopWhatsappUrl(shop);
  const rating = Number(shop.rating) || 0;
  const reviews = Number(shop.reviewCount) || 0;
  const productCount = Math.max(Number(shop.listingCount) || 0, listings.length);
  const types = listingTypes(listings);
  const rails = deriveRails(listings);
  const deepLink = deepLinkForShop(shop.id);
  const initial = esc(String(name).trim().charAt(0).toUpperCase() || "S");

  const topbar = standalone
    ? `<div class="sd-topbar"><a class="sd-brand" href="/">Swift</a><a class="sd-link" href="/market">Browse market</a></div>`
    : `<a class="sd-back" href="/market">‹ Market</a>`;

  const meta = [
    shop.category ? `<span class="sd-chip-cat">${esc(shop.category)}</span>` : "",
    rating > 0 ? `<span class="sd-rating" title="${rating.toFixed(1)} out of 5"><span aria-hidden="true">★</span> ${rating.toFixed(1)}${reviews ? ` <span class="sd-muted">(${reviews})</span>` : ""}</span>` : "",
    shop.isActive === false ? `<span class="sd-chip-off">Not taking orders</span>` : ""
  ].filter(Boolean).join("");

  const actions = [
    wa ? `<a class="sd-btn sd-btn-wa" href="${esc(wa)}" target="_blank" rel="noopener noreferrer">💬 Message on WhatsApp</a>` : "",
    coords ? `<a class="sd-btn" href="${esc(directionsUrl(coords))}" target="_blank" rel="noopener noreferrer">🧭 Directions</a>` : "",
    `<button type="button" class="sd-btn" data-sd-share>Share</button>`,
    `<button type="button" class="sd-btn sd-btn-ghost" data-sd-follow>＋ Follow</button>`
  ].filter(Boolean).join("");

  const stats = `
    <dl class="sd-stats">
      <div><dt>Products</dt><dd>${compact(productCount)}</dd></div>
      <div><dt>Followers</dt><dd>${compact(shop.followerCount)}</dd></div>
      <div><dt>Reviews</dt><dd>${reviews > 0 ? compact(reviews) : "New"}</dd></div>
    </dl>`;

  const place = (coords || shop.locationAddress) ? `
      <div class="sd-place">
        <span aria-hidden="true">📍</span>
        <span class="sd-place-text">${esc(shop.locationAddress || "Shop location")}</span>
        ${coords ? `<a class="sd-link" href="${esc(directionsUrl(coords))}" target="_blank" rel="noopener noreferrer">Directions</a>` : ""}
      </div>` : "";

  const toolbar = listings.length ? `
    <div class="sd-toolbar">
      <label class="sd-search"><span class="ui-sr">Search this shop</span>
        <input type="search" data-sd-q placeholder="Search ${esc(name)}" autocomplete="off" enterkeyhint="search">
      </label>
      <label class="sd-sort"><span class="ui-sr">Sort products</span>
        <select data-sd-sort>${Object.entries(SORTS).map(([v, l]) => `<option value="${esc(v)}">${esc(l)}</option>`).join("")}</select>
      </label>
    </div>
    ${types.length > 1 ? `<div class="sd-chips" role="group" aria-label="Filter by type">
      <button type="button" class="sd-chip is-on" data-sd-type="ALL" aria-pressed="true">All (${listings.length})</button>
      ${types.map((t) => `<button type="button" class="sd-chip" data-sd-type="${esc(t.type)}" aria-pressed="false">${esc(t.label)} (${t.count})</button>`).join("")}
    </div>` : ""}
    <p class="sd-count" data-sd-count aria-live="polite"></p>
    <div class="sd-grid" data-sd-grid></div>
    <div class="sd-empty" data-sd-empty hidden>
      <div class="sd-empty-icon" aria-hidden="true">🔎</div>
      <strong>No products match</strong>
      <span class="sd-muted">Try a different word or clear the filters.</span>
      <button type="button" class="sd-btn" data-sd-clear>Clear filters</button>
    </div>` : `
    <div class="sd-empty">
      <div class="sd-empty-icon" aria-hidden="true">🛍️</div>
      <strong>No products yet</strong>
      <span class="sd-muted">${esc(name)} hasn't listed anything. Check back soon.</span>
    </div>`;

  return `
  <div class="sd${standalone ? " sd-standalone" : ""}" data-sd data-shop-id="${esc(shop.id)}">
    <div class="sd-top">${topbar}</div>

    <header class="sd-hero">
      <div class="sd-cover${cover ? "" : " is-empty"}">
        ${cover ? `<img class="sd-cover-img" src="${esc(cover)}" alt="" loading="eager">` : ""}
        <span class="sd-scrim"></span>
      </div>
      <div class="sd-card">
        <div class="sd-idrow">
          ${logo ? `<img class="sd-logo" src="${esc(logo)}" alt="${esc(name)} logo">` : `<span class="sd-logo sd-logo-fallback" aria-hidden="true">${initial}</span>`}
          <div class="sd-idtext">
            <h1 class="sd-name">${esc(name)}${shop.isVerified ? ` <span class="sd-verified" title="Verified shop" aria-label="Verified shop">✔</span>` : ""}</h1>
            ${meta ? `<div class="sd-meta">${meta}</div>` : ""}
          </div>
        </div>
        <div class="sd-actions">${actions}</div>
        <div class="sd-apppanel" data-sd-apppanel hidden role="status">
          Follow and message ${esc(name)} in the Swift app.
          <div class="sd-apppanel-links">
            <a class="sd-btn sd-btn-primary" href="${esc(deepLink)}">Open in Swift app</a>
            <a class="sd-btn" href="${esc(APP_LINKS.playStore)}" target="_blank" rel="noopener noreferrer">Get it on Google Play</a>
          </div>
        </div>
        ${shop.description ? `<p class="sd-desc" data-sd-desc>${esc(shop.description)}</p><button type="button" class="sd-more" data-sd-more hidden aria-expanded="false">Read more</button>` : ""}
        ${stats}
        ${place}
      </div>
    </header>

    ${railHTML({ id: "shop-popular", title: "Popular right now", subtitle: `Most-loved from ${name}`, items: rails.popular, renderItem: cardHTML })}
    ${railHTML({ id: "shop-new", title: "New arrivals", items: rails.newest, renderItem: cardHTML })}

    <section class="sd-products" aria-label="Products">
      <h2 class="sd-h2">Products</h2>
      ${toolbar}
    </section>

    ${railHTML({ id: "shop-others", title: "More shops to explore", items: otherShops, renderItem: otherShopChip, seeAllHref: "/market", seeAllLabel: "Browse market" })}
    <div class="ld-toast uk-toast" data-uk-toast role="status" aria-live="polite"></div>
  </div>`;
}

// ---------- Behaviour ----------

function toast(root, message) {
  const el = root.querySelector("[data-uk-toast]");
  if (!el) return;
  el.textContent = message;
  el.classList.add("is-on");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.remove("is-on"), 2600);
}

async function share(root, { url, title }) {
  if (typeof navigator !== "undefined" && navigator.share) {
    try { await navigator.share({ title, url }); return; }
    catch (err) { if (err && err.name === "AbortError") return; }
  }
  try {
    await navigator.clipboard.writeText(url);
    toast(root, "Link copied");
  } catch {
    window.prompt("Copy this link", url);
  }
}

export function bindShopDetail(root, ctx) {
  const { shop, listings = [], cardHTML } = ctx;
  const state = { q: "", type: "ALL", sort: "newest" };
  const grid = root.querySelector("[data-sd-grid]");
  const count = root.querySelector("[data-sd-count]");
  const empty = root.querySelector("[data-sd-empty]");
  const qInput = root.querySelector("[data-sd-q]");
  const sortSel = root.querySelector("[data-sd-sort]");

  const renderGrid = () => {
    if (!grid) return;
    const rows = filterListings(listings, state);
    grid.innerHTML = rows.map((l) => cardHTML(l)).join("");
    grid.hidden = rows.length === 0;
    if (empty) empty.hidden = rows.length !== 0;
    if (count) {
      const filtered = state.q.trim() || state.type !== "ALL";
      count.textContent = filtered
        ? `${rows.length} of ${listings.length} product${listings.length === 1 ? "" : "s"}`
        : `${listings.length} product${listings.length === 1 ? "" : "s"}`;
    }
  };
  renderGrid();

  let timer;
  qInput?.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => { state.q = qInput.value; renderGrid(); }, 120);
  });
  sortSel?.addEventListener("change", () => { state.sort = sortSel.value; renderGrid(); });
  root.querySelectorAll("[data-sd-type]").forEach((chip) => chip.addEventListener("click", () => {
    state.type = chip.dataset.sdType;
    root.querySelectorAll("[data-sd-type]").forEach((c) => {
      const on = c === chip;
      c.classList.toggle("is-on", on);
      c.setAttribute("aria-pressed", on ? "true" : "false");
    });
    renderGrid();
  }));
  root.querySelector("[data-sd-clear]")?.addEventListener("click", () => {
    state.q = ""; state.type = "ALL"; state.sort = "newest";
    if (qInput) qInput.value = "";
    if (sortSel) sortSel.value = "newest";
    root.querySelectorAll("[data-sd-type]").forEach((c) => {
      const on = c.dataset.sdType === "ALL";
      c.classList.toggle("is-on", on);
      c.setAttribute("aria-pressed", on ? "true" : "false");
    });
    renderGrid();
  });

  root.querySelector("[data-sd-share]")?.addEventListener("click", () => share(root, { url: ctx.shareUrl, title: shop.name || "SwiftShop" }));
  root.querySelector("[data-sd-follow]")?.addEventListener("click", () => {
    const panel = root.querySelector("[data-sd-apppanel]");
    if (panel) { panel.hidden = false; panel.scrollIntoView?.({ behavior: "smooth", block: "nearest" }); }
  });

  // Description: clamp to 3 lines, offer "Read more" only when it actually overflows.
  const desc = root.querySelector("[data-sd-desc]");
  const more = root.querySelector("[data-sd-more]");
  if (desc && more) {
    if (desc.scrollHeight > desc.clientHeight + 2) more.hidden = false;
    more.addEventListener("click", () => {
      const open = desc.classList.toggle("is-open");
      more.textContent = open ? "Show less" : "Read more";
      more.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  bindRails(root);
}
