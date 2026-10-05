// SwiftShop Web – Listing Detail view (parity with Android ListingDetailScreen).
//
// Pure presentation: no Firebase imports and no commerce logic. Data and actions are
// injected by hosting/app.js (SPA /listing/:id route and the server-rendered /s/ and /d/
// share pages both mount this same view). Purchase / delivery / payment authority stays
// server-side in functions/src – this file never decides prices, stock or fulfilment.
//
// Social write actions (like, bookmark, comment, seller chat) need a signed-in Swift
// account, which the Web does not have yet, so they are shown as read-only counts with a
// pointer to the app instead of fake buttons.

const esc = (s) => String(s ?? "")
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
  .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

const safeUrl = (u) => (/^https?:\/\//i.test(String(u || "")) ? String(u) : "");
const money = (minor) => `M${((Number(minor) || 0) / 100).toFixed(2)}`;

// Mirrors Android ctaFor(ListingType): label + what the Web can do for that type.
//   purchase   – cart / checkout flow (canonical calculatePurchaseTotal / createPurchaseOrder)
//   orders     – delivery is attached to a paid order (Android sends the buyer to Orders)
//   directions – open maps to the shop
//   app        – needs a form / flow that only exists in the Swift app today
export const CTA = {
  BUY:             { label: "Buy Now",          icon: "🛒", kind: "purchase" },
  PLACE_ORDER:     { label: "Place Order",      icon: "🚚", kind: "purchase" },
  MAKE_PAYMENT:    { label: "Make Payment",     icon: "💳", kind: "app" },
  SET_APPOINTMENT: { label: "Book Appointment", icon: "📅", kind: "app" },
  REGISTER:        { label: "Register",         icon: "📝", kind: "app" },
  DELIVER:         { label: "Request Delivery", icon: "🛵", kind: "orders" },
  TAKE_ME_THERE:   { label: "Take Me There",    icon: "🧭", kind: "directions" }
};

export const APP_LINKS = {
  playStore: "https://play.google.com/store/apps/details?id=com.swiftshop.client",
  deepLink: (listingId) => `swiftshop://listing/${encodeURIComponent(listingId)}`
};

export function ctaFor(type) {
  return CTA[String(type || "BUY")] || CTA.BUY;
}

export function stockInfo(listing) {
  const qty = Number(listing.stockQuantity);
  const stock = Number.isFinite(qty) ? Math.max(0, Math.floor(qty)) : 0;
  const available = listing.isAvailable !== false && stock > 0;
  return { stock, available, label: available ? `${stock} in stock` : "Out of stock" };
}

export function shopCoords(shop) {
  if (!shop) return null;
  const lat = Number(shop.locationLat ?? shop.location?.lat ?? 0);
  const lng = Number(shop.locationLng ?? shop.location?.lng ?? 0);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0)) return null;
  return { lat, lng };
}

export function directionsUrl(coords) {
  return `https://www.google.com/maps/dir/?api=1&destination=${coords.lat},${coords.lng}`;
}

// ---------- HTML ----------

function galleryHTML(images, title) {
  const imgs = (images || []).map(safeUrl).filter(Boolean);
  if (!imgs.length) {
    return `<div class="ld-gallery"><div class="ld-noimg" role="img" aria-label="No image available">No image</div></div>`;
  }
  const multi = imgs.length > 1;
  const slides = imgs.map((u, i) =>
    `<img class="ld-slide" src="${esc(u)}" alt="${esc(title)}${multi ? ` – image ${i + 1} of ${imgs.length}` : ""}" loading="${i === 0 ? "eager" : "lazy"}" draggable="false">`
  ).join("");
  const nav = multi ? `
      <button type="button" class="ld-nav ld-prev" data-ld-prev aria-label="Previous image">‹</button>
      <button type="button" class="ld-nav ld-next" data-ld-next aria-label="Next image">›</button>
      <div class="ld-dots" data-ld-dots aria-hidden="true">${imgs.map((_, i) => `<span class="ld-dot${i === 0 ? " is-on" : ""}"></span>`).join("")}</div>` : "";
  const thumbs = multi ? `
    <div class="ld-thumbs">${imgs.map((u, i) =>
      `<button type="button" class="ld-thumb${i === 0 ? " is-on" : ""}" data-ld-thumb="${i}" aria-label="Show image ${i + 1}"><img src="${esc(u)}" alt="" loading="lazy"></button>`
    ).join("")}</div>` : "";
  return `
    <div class="ld-gallery" data-count="${imgs.length}">
      <div class="ld-track" data-ld-track tabindex="0" role="group" aria-label="Product images">${slides}</div>${nav}
    </div>${thumbs}`;
}

function sellerHTML(listing, shop, isDelivery) {
  const name = shop?.name || "Shop";
  const logo = safeUrl(shop?.logoUrl);
  const coords = shopCoords(shop);
  const address = shop?.locationAddress || "";
  const shopHref = `/shop/${encodeURIComponent(listing.shopId || shop?.id || "")}`;
  const logoHTML = logo
    ? `<img class="ld-logo" src="${esc(logo)}" alt="" loading="lazy">`
    : `<span class="ld-logo ld-logo-fallback" aria-hidden="true">🏪</span>`;
  const place = (coords || address) ? `
      <div class="ld-place">
        <span aria-hidden="true">📍</span>
        <span class="ld-place-text">${esc(address || "Shop location")}</span>
        ${coords ? `<a class="ld-link" href="${esc(directionsUrl(coords))}" target="_blank" rel="noopener noreferrer">Directions</a>` : ""}
      </div>` : "";
  return `
    <a class="ld-seller" href="${esc(shopHref)}">
      ${logoHTML}
      <span class="ld-seller-text">
        <span class="ld-eyebrow">${isDelivery ? "Delivery service by" : "Sold by"}</span>
        <span class="ld-seller-name">${esc(name)}${shop?.isVerified ? ` <span class="ld-verified" title="Verified shop" aria-label="Verified shop">✔</span>` : ""}</span>
      </span>
      <span class="ld-visit">Visit Shop ›</span>
    </a>${place}`;
}

function customFieldsHTML(fields) {
  const rows = (fields || []).filter(f => f && f.label).map(f => {
    const opts = Array.isArray(f.options) && f.options.length ? ` · ${f.options.map(esc).join(", ")}` : "";
    return `<li><strong>${esc(f.label)}</strong>${f.isRequired ? ` <span class="ld-req">required</span>` : ""}<span class="ld-muted"> (${esc(f.type || "text")}${opts})</span></li>`;
  });
  if (!rows.length) return "";
  return `<section class="ld-section"><h2>Options</h2><ul class="ld-options">${rows.join("")}</ul></section>`;
}

function tagsHTML(tags) {
  const list = (tags || []).filter(Boolean);
  if (!list.length) return "";
  return `<div class="ld-tags">${list.map(t => `<span class="ld-tag">#${esc(String(t).replace(/^#/, ""))}</span>`).join("")}</div>`;
}

function railHTML(title, items, cardHTML) {
  if (!items || !items.length || typeof cardHTML !== "function") return "";
  return `<section class="ld-rail"><h2>${esc(title)}</h2><div class="ld-rail-track">${items.map(cardHTML).join("")}</div></section>`;
}

export function renderListingDetailHTML(ctx) {
  const { listing: l, shop = null, moreFromShop = [], similar = [], cardHTML, standalone = false } = ctx;
  const cta = ctaFor(l.listingType);
  const isDelivery = String(l.listingType) === "DELIVER";
  const stock = stockInfo(l);
  const purchasable = cta.kind === "purchase";
  const coords = shopCoords(shop);
  const canAct = purchasable ? stock.available : (cta.kind === "directions" ? !!coords : true);
  const maxQty = Math.max(1, Math.min(stock.stock || 1, 99));
  const deepLink = APP_LINKS.deepLink(l.id);

  const topbar = standalone
    ? `<div class="ld-topbar"><a class="ld-brand" href="/">Swift</a><a class="ld-link" href="/market">Browse market</a></div>`
    : `<a class="ld-back" href="${esc(shop ? `/shop/${encodeURIComponent(l.shopId)}` : "/market")}">‹ ${shop ? esc(shop.name || "Shop") : "Market"}</a>`;

  const stockChip = purchasable
    ? `<span class="ld-stock ${stock.available ? "is-ok" : "is-out"}">${esc(stock.label)}</span>` : "";

  const qtyBlock = purchasable ? `
      <div class="ld-qty" role="group" aria-label="Quantity">
        <button type="button" class="ld-qty-btn" data-ld-qty-dec aria-label="Decrease quantity" ${stock.available ? "" : "disabled"}>−</button>
        <input class="ld-qty-input" data-ld-qty type="number" inputmode="numeric" min="1" max="${maxQty}" value="1" aria-label="Quantity" ${stock.available ? "" : "disabled"}>
        <button type="button" class="ld-qty-btn" data-ld-qty-inc aria-label="Increase quantity" ${stock.available ? "" : "disabled"}>+</button>
      </div>
      <button type="button" class="ld-iconbtn" data-ld-addcart aria-label="Add to cart" title="Add to cart" ${stock.available ? "" : "disabled"}>＋🛒</button>` : "";

  const meta = [
    Number(l.commitmentCount) > 0 ? `<span class="ld-pill">👥 ${Number(l.commitmentCount)} verified buyers</span>` : "",
    Number(l.deliveryEstimateDays) > 0 ? `<span class="ld-pill">🚚 Estimated delivery: ${Number(l.deliveryEstimateDays)} days</span>` : ""
  ].filter(Boolean).join("");

  return `
  <div class="ld${standalone ? " ld-standalone" : ""}" data-ld data-listing-id="${esc(l.id)}">
    <div class="ld-top">${topbar}</div>

    <section class="ld-media">${galleryHTML(l.imageUrls, l.title || "Product")}</section>

    <section class="ld-info">
      <div class="ld-pricerow">
        <div class="ld-price">${esc(money(l.priceMinorUnits))}</div>
        ${stockChip}
      </div>
      <h1 class="ld-title">${esc(l.title || "Untitled")}</h1>
      <div class="ld-stats">
        <span class="ld-stat" title="Likes">♥ ${Number(l.likeCount) || 0}</span>
        <span class="ld-stat" title="Comments">💬 ${Number(l.commentCount) || 0}</span>
        <span class="ld-stat" title="Saves">🔖 ${Number(l.bookmarkCount) || 0}</span>
        <button type="button" class="ld-share" data-ld-share>Share</button>
      </div>
      ${meta ? `<div class="ld-meta">${meta}</div>` : ""}
      <p class="ld-appnote">Like, save, comment and message the seller in the Swift app.
        <a class="ld-link" href="${esc(deepLink)}">Open in app</a> ·
        <a class="ld-link" href="${esc(APP_LINKS.playStore)}" target="_blank" rel="noopener noreferrer">Get it on Google Play</a></p>
      <div class="ld-apppanel" data-ld-apppanel hidden role="status">
        <strong>${esc(cta.label)}</strong> is completed in the Swift app.
        <div class="ld-apppanel-links">
          <a class="btn primary" href="${esc(deepLink)}">Open in Swift app</a>
          <a class="btn secondary" href="${esc(APP_LINKS.playStore)}" target="_blank" rel="noopener noreferrer">Get it on Google Play</a>
        </div>
      </div>
    </section>

    <section class="ld-sellerbox">${sellerHTML(l, shop, isDelivery)}</section>

    <section class="ld-body">
      <section class="ld-section"><h2>Description</h2>
        <p class="ld-desc">${l.description ? esc(l.description) : `<span class="ld-muted">The seller hasn't added a description yet.</span>`}</p>
      </section>
      ${customFieldsHTML(l.customFields)}
      ${tagsHTML(l.tags)}
    </section>

    <div class="ld-rails">
      ${railHTML("More from this shop", moreFromShop, cardHTML)}
      ${railHTML("You might also like", similar, cardHTML)}
    </div>

    <div class="ld-buy" data-ld-buy>
      ${qtyBlock}
      <button type="button" class="ld-cta" data-ld-cta data-kind="${esc(cta.kind)}" ${canAct ? "" : "disabled"}>
        <span aria-hidden="true">${cta.icon}</span> ${esc(!canAct && purchasable ? "Out of stock" : cta.label)}
      </button>
    </div>
    <div class="ld-toast" data-ld-toast role="status" aria-live="polite"></div>
  </div>`;
}

// ---------- Behaviour ----------

function toast(root, message) {
  const el = root.querySelector("[data-ld-toast]");
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

export function bindListingDetail(root, ctx, actions) {
  const l = ctx.listing;
  const cta = ctaFor(l.listingType);
  const stock = stockInfo(l);
  const maxQty = Math.max(1, Math.min(stock.stock || 1, 99));
  const track = root.querySelector("[data-ld-track]");
  const qtyInput = root.querySelector("[data-ld-qty]");

  const qty = () => {
    const n = Math.floor(Number(qtyInput?.value));
    return Number.isFinite(n) ? Math.min(maxQty, Math.max(1, n)) : 1;
  };
  const setQty = (n) => { if (qtyInput) qtyInput.value = String(Math.min(maxQty, Math.max(1, n))); };

  // Gallery: scroll-snap track, dots + thumbs follow the visible slide.
  if (track) {
    const dots = [...root.querySelectorAll(".ld-dot")];
    const thumbs = [...root.querySelectorAll("[data-ld-thumb]")];
    const count = track.children.length;
    const index = () => Math.round(track.scrollLeft / Math.max(1, track.clientWidth));
    const goTo = (i) => track.scrollTo({ left: Math.max(0, Math.min(count - 1, i)) * track.clientWidth, behavior: "smooth" });
    track.addEventListener("scroll", () => {
      const i = index();
      dots.forEach((d, n) => d.classList.toggle("is-on", n === i));
      thumbs.forEach((t, n) => t.classList.toggle("is-on", n === i));
    }, { passive: true });
    root.querySelector("[data-ld-prev]")?.addEventListener("click", () => goTo(index() - 1));
    root.querySelector("[data-ld-next]")?.addEventListener("click", () => goTo(index() + 1));
    thumbs.forEach((t) => t.addEventListener("click", () => goTo(Number(t.dataset.ldThumb))));
    track.addEventListener("keydown", (e) => {
      if (e.key === "ArrowLeft") { e.preventDefault(); goTo(index() - 1); }
      if (e.key === "ArrowRight") { e.preventDefault(); goTo(index() + 1); }
    });
  }

  // Quantity stepper.
  root.querySelector("[data-ld-qty-dec]")?.addEventListener("click", () => setQty(qty() - 1));
  root.querySelector("[data-ld-qty-inc]")?.addEventListener("click", () => setQty(qty() + 1));
  qtyInput?.addEventListener("change", () => setQty(qty()));

  // Add to cart (purchase types only; server re-validates stock and price at checkout).
  const addBtn = root.querySelector("[data-ld-addcart]");
  addBtn?.addEventListener("click", () => {
    actions.addToCart(l.id, l.title, qty());
    addBtn.textContent = "Added ✓";
    toast(root, "Added to cart");
    clearTimeout(addBtn._t);
    addBtn._t = setTimeout(() => { addBtn.textContent = "＋🛒"; }, 1800);
  });

  // Primary CTA, driven by listing type exactly as Android's ctaFor().
  root.querySelector("[data-ld-cta]")?.addEventListener("click", () => {
    if (cta.kind === "purchase") {
      actions.addToCart(l.id, l.title, qty());
      actions.navigate("/checkout");
    } else if (cta.kind === "orders") {
      actions.navigate("/orders");
    } else if (cta.kind === "directions") {
      const coords = shopCoords(ctx.shop);
      if (coords) window.open(directionsUrl(coords), "_blank", "noopener");
    } else {
      const panel = root.querySelector("[data-ld-apppanel]");
      if (panel) { panel.hidden = false; panel.scrollIntoView({ behavior: "smooth", block: "center" }); }
    }
  });

  root.querySelector("[data-ld-share]")?.addEventListener("click", () => {
    share(root, { url: ctx.shareUrl, title: l.title || "SwiftShop" });
  });
}
