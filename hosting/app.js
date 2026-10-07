// SwiftShop Web – real Firestore-backed marketplace client.
// Loaded as a module by index.html (browse / shop / cart / checkout routes)
// and, in "widget" mode, by the server-rendered /listing/{id} page for the
// Add to Cart / Buy Now buttons.
//
// NOTE: this file only ever calls existing Cloud Functions (calculatePurchaseTotal,
// createPurchaseOrder) – it does not implement any order/payment/inventory logic itself.
// That authority stays server-side in functions/src/commerce.ts.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInAnonymously
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js";
import {
  getFunctions, httpsCallable
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-functions.js";
import { getFirestore, collection, query, where, onSnapshot, getDoc, doc, updateDoc } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-firestore.js";
import { getStorage, ref as storageRef, uploadBytes, getDownloadURL } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-storage.js";

import { renderListingDetailHTML, bindListingDetail } from "./listing-detail.js";
import { renderShopDetailHTML, bindShopDetail } from "./shop-detail.js";

// Firebase Hosting exposes the configuration for the project serving this page.
// This keeps Dev, Staging, and Production aligned with their Hosting target.
const firebaseConfig = await fetch("/__/firebase/init.json").then(async response => {
  if (!response.ok) {
    throw new Error(`Firebase Hosting config returned HTTP ${response.status}`);
  }
  return response.json();
});

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const functions = getFunctions(app);
const db = getFirestore(app);
const storage = getStorage(app);

const CART_KEY = "swiftshop_cart_v1";
const LSL = (minorUnits) => `M${(minorUnits / 100).toFixed(2)}`;
let frontDoorTimer = null;

// ---------- Cart (client-side convenience only – never authoritative) ----------

function getCart() {
  try { return JSON.parse(localStorage.getItem(CART_KEY)) || []; }
  catch { return []; }
}
function saveCart(items) {
  localStorage.setItem(CART_KEY, JSON.stringify(items));
  updateCartBadge();
  document.dispatchEvent(new CustomEvent("swift:cart-updated"));
}
function addToCart(listingId, title, qty = 1) {
  const items = getCart();
  const existing = items.find(i => i.listingId === listingId);
  if (existing) existing.quantity += qty;
  else items.push({ listingId, title, quantity: qty });
  saveCart(items);
}
function removeFromCart(listingId) {
  saveCart(getCart().filter(i => i.listingId !== listingId));
}
function setQty(listingId, qty) {
  const items = getCart();
  const it = items.find(i => i.listingId === listingId);
  if (!it) return;
  if (qty <= 0) return removeFromCart(listingId);
  it.quantity = qty;
  saveCart(items);
}
function cartCount() {
  return getCart().reduce((n, i) => n + i.quantity, 0);
}
function updateCartBadge() {
  document.querySelectorAll("[data-cart-badge]").forEach(el => {
    const n = cartCount();
    el.textContent = n > 0 ? String(n) : "";
    el.style.display = n > 0 ? "inline-block" : "none";
  });
}

// ---------- Auth (anonymous – enough to call authenticated callables) ----------

function ensureSignedIn() {
  return new Promise((resolve, reject) => {
    const unsub = onAuthStateChanged(auth, (user) => {
      unsub();
      if (user) return resolve(user);
      signInAnonymously(auth).then(cred => resolve(cred.user)).catch(reject);
    });
  });
}

// ---------- Data (server-backed marketplace API) ----------

let marketplacePromise = null;

async function fetchMarketplace() {
  if (!marketplacePromise) {
    marketplacePromise = fetch("/api/marketplace", {
      headers: { "Accept": "application/json" },
      cache: "no-store"
    }).then(async response => {
      const data = await response.json().catch(() => null);
      if (!response.ok || !data?.ok) {
        throw new Error(data?.error || `Marketplace API returned HTTP ${response.status}`);
      }
      return data;
    });
  }

  try {
    return await marketplacePromise;
  } catch (error) {
    marketplacePromise = null;
    throw error;
  }
}

async function fetchListings({ max = 24 } = {}) {
  const data = await fetchMarketplace();
  return (data.listings || []).slice(0, max);
}

async function fetchShops({ max = 100 } = {}) {
  const data = await fetchMarketplace();
  return (data.shops || []).slice(0, max);
}

async function fetchShop(shopId) {
  const data = await fetchMarketplace();
  return (data.shops || []).find(s => s.id === shopId) || null;
}

async function fetchShopListings(shopId, { max = 48 } = {}) {
  const data = await fetchMarketplace();
  return (data.listings || [])
    .filter(l => l.shopId === shopId)
    .slice(0, max);
}

async function fetchListing(listingId) {
  const data = await fetchMarketplace();
  return (data.listings || []).find(l => l.id === listingId) || null;
}

// ---------- Rendering helpers ----------

function el(html) {
  const t = document.createElement("template");
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
}

function normalizeShareSlug(value) {
  return String(value || "")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .replace(/-{2,}/g, "-");
}

function listingCard(l, shopById) {
  // Guard: Array.map(listingCard) would pass the numeric index here.
  const shopLookup = shopById instanceof Map ? shopById : new Map();
  const img = (l.imageUrls && l.imageUrls[0]) || "";
  const shop = shopLookup.get(l.shopId);
  const shopSlug = shop?.shareSlug || normalizeShareSlug(shop?.name || "shop");
  const productSlug = l.shareSlug || normalizeShareSlug(l.title || l.id);
  const sharePath = "/s/" + encodeURIComponent(shopSlug) + "/" + encodeURIComponent(productSlug);
  return `
    <a class="card" href="${sharePath}">
      <div class="card-img" style="background-image:url('${img}')"></div>
      <div class="card-body">
        <div class="card-title">${escapeHtml(l.title || "Untitled")}</div>
        <div class="card-price">${LSL(l.priceMinorUnits || 0)}</div>
      </div>
    </a>`;
}

function shopCard(s) {
  const category = String(s.category || "Local shop").trim();
  const description = String(s.description || "").trim();
  return `
    <a class="shop-card" href="/shop/${encodeURIComponent(s.id)}" aria-label="Visit ${escapeHtml(s.name || "Shop")}">
      <div class="shop-card-cover" style="background-image:url('${s.coverUrl || ""}')"></div>
      <div class="shop-card-scrim"></div>
      <div class="shop-card-content">
        <div class="shop-card-top">
          <div class="shop-logo" style="background-image:url('${s.logoUrl || ""}')"></div>
          <span class="shop-card-category">${escapeHtml(category)}</span>
        </div>
        <div class="shop-card-bottom">
          <div class="shop-name">${escapeHtml(s.name || "Shop")}</div>
          ${description ? `<div class="shop-description">${escapeHtml(description.length > 78 ? description.slice(0, 75) + "…" : description)}</div>` : ""}
          <span class="shop-card-link">Visit shop <span aria-hidden="true">→</span></span>
        </div>
      </div>
    </a>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

// ---------- Views ----------

async function renderBrowse(root, direct = false) {
  if (frontDoorTimer) { clearInterval(frontDoorTimer); frontDoorTimer = null; }

  const marketPromise = Promise.all([fetchListings(), fetchShops()]);

  // /market always goes straight to the feed.
  // / (home) for signed-in users shows the profile screen.
  const user = auth.currentUser;
  const signedIn = user && !user.isAnonymous;
  if (direct) {
    document.body.classList.remove("home-profile-route");
    root.innerHTML = `<div class="loading">Loading market…</div>`;
    try {
      const [listings, shops] = await marketPromise;
      renderMarketFeed(root, listings, shops);
    } catch (err) {
      root.innerHTML = `<div class="error">Couldn't load the market. <button class="btn secondary" type="button" data-retry>Try again</button></div>`;
      root.querySelector("[data-retry]")?.addEventListener("click", () => renderBrowse(root, direct));
    }
    return;
  }
  if (signedIn) {
    renderHomeProfile(root, user, marketPromise);
    return;
  }

  // Home for guests: front-door with signup form
  root.innerHTML = `
    <section class="market-front-door" aria-label="Swift marketplace welcome">
      <div class="ad-slot" data-ad-provider="google-meta" aria-label="Advertisement">
        <span class="ad-label">Advertisement</span>
        <strong>Google / Meta ad space</strong>
        <small>Reserved for marketplace advertising</small>
      </div>

      <div class="swift-house-ad" aria-label="Swift promotion">
        <div class="swift-house-copy">
          <span class="ad-label">Swift</span>
          <div class="swift-house-slide is-active" data-house-slide="0">
            <strong>Local market. One place.</strong>
            <span>Discover products from local businesses around Maseru.</span>
          </div>
          <div class="swift-house-slide" data-house-slide="1">
            <strong>Shop local with Swift.</strong>
            <span>Find something you need, buy it, and keep moving.</span>
          </div>
          <div class="swift-house-slide" data-house-slide="2">
            <strong>Businesses belong on Swift.</strong>
            <span>Put your products in front of people already looking to buy.</span>
          </div>
        </div>
        <div class="swift-house-dots" aria-hidden="true">
          <span class="is-active" data-house-dot="0"></span>
          <span data-house-dot="1"></span>
          <span data-house-dot="2"></span>
        </div>
      </div>

      <div class="guest-entry guest-entry-form">
        <div class="guest-entry-header">
          <span class="eyebrow">Swift marketplace</span>
          <h2 id="guestEntryTitle">Ready to shop?</h2>
          <p id="guestEntrySubtitle">Create your account to buy, track orders and follow shops.</p>
        </div>
        <div id="formWrap">
          <form class="signup-form" id="signupForm" data-mode="signup" novalidate>
            <div class="signup-form-row" id="nameRow">
              <input type="text" id="signupFirstName" placeholder="First name" autocomplete="given-name">
              <input type="text" id="signupLastName" placeholder="Last name" autocomplete="family-name">
            </div>
            <input type="email" id="signupEmail" placeholder="Email address" autocomplete="email" required>
            <input type="tel" id="signupPhone" placeholder="Phone number (optional)" autocomplete="tel" id="phoneRow">
            <input type="password" id="signupPassword" placeholder="Create a password" autocomplete="new-password" required>
                <p class="signup-form-error" id="signupError" style="display:none"></p>
            <button class="btn primary guest-btn" type="submit" id="signupSubmitBtn">Create account <span aria-hidden="true">→</span></button>
            <p class="signup-form-note">Already have Swift? <button type="button" class="text-btn" id="switchToSignin">Sign in</button></p>
            <p class="signup-form-note" style="margin-top:4px"><button type="button" class="text-btn" id="browseGuestBtn">Browse as guest →</button></p>
          </form>
        </div>
        <div id="marketReady" role="status" aria-live="polite" style="font-size:13px;color:var(--muted);margin-top:6px;min-height:18px"></div>
      </div>
    </section>`;

  const slides = [...root.querySelectorAll("[data-house-slide]")];
  const dots   = [...root.querySelectorAll("[data-house-dot]")];
  let active   = 0;
  const showSlide = (index) => {
    active = index % slides.length;
    slides.forEach((s, i) => s.classList.toggle("is-active", i === active));
    dots.forEach((d, i) => d.classList.toggle("is-active", i === active));
  };
  frontDoorTimer = setInterval(() => showSlide(active + 1), 4200);

  // Browse as guest — load feed without auth
  root.querySelector("#browseGuestBtn")?.addEventListener("click", async () => {
    const btn = root.querySelector("#browseGuestBtn");
    if (btn) { btn.disabled = true; btn.textContent = "Loading market…"; }
    try {
      const [listings, shops] = await marketPromise;
      if (frontDoorTimer) { clearInterval(frontDoorTimer); frontDoorTimer = null; }
      renderMarketFeed(root, listings, shops);
    } catch (err) {
      if (btn) { btn.disabled = false; btn.textContent = "Browse as guest →"; }
    }
  });

  bindSignupForm(root, marketPromise);
}

async function renderShops(root) {
  root.innerHTML = `
    <section class="rail-page shops-page">
      <div class="section-heading">
        <span class="eyebrow">Discover local businesses</span>
        <h1>All Shops</h1>
        <p class="section-subtitle">Explore shops on Swift and open a shop to see its listings.</p>
      </div>
      <div id="shopsGrid" class="shop-directory"><div class="loading">Loading shops…</div></div>
    </section>`;
  try {
    const shops = await fetchShops({ max: 100 });
    const grid = root.querySelector("#shopsGrid");
    grid.innerHTML = shops.length
      ? shops.map(shopCard).join("")
      : "<p class='empty'>No shops yet.</p>";
  } catch (err) {
    root.querySelector("#shopsGrid").innerHTML = `<div class="error">Couldn't load shops. ${escapeHtml(err.message)}</div>`;
  }
}

async function renderShop(root, shopId) {
  root.innerHTML = `<div class="loading">Loading shop…</div>`;
  await mountShopDetail(root, { shopId });
}

// Mounts the Shop Detail view into `root`. Used by the SPA (/shop/:id) and by the server-rendered
// /shop/:id share page (standalone). Returns false when the shop can't be shown so the caller can fall back.
async function mountShopDetail(root, { shopId, standalone = false } = {}) {
  try {
    const data = await fetchMarketplace();
    const shops = data.shops || [];
    const shop = shops.find(s => s.id === shopId);
    if (!shop) {
      root.innerHTML = `<div class="error">Shop not found.</div>`;
      return false;
    }
    const shopById = new Map(shops.map(s => [s.id, s]));
    const listings = (data.listings || []).filter(l => l.shopId === shopId);
    const otherShops = shops.filter(s => s.id !== shopId && s.isActive !== false).slice(0, 12);
    const ctx = {
      shop, listings, otherShops, standalone,
      cardHTML: (l) => listingCard(l, shopById),
      shareUrl: location.origin + "/shop/" + encodeURIComponent(shop.id)
    };
    root.innerHTML = renderShopDetailHTML(ctx);
    bindShopDetail(root, ctx);
    if (!standalone && shop.name) document.title = `${shop.name} — SwiftShop`;
    return true;
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this shop. ${escapeHtml(err.message)} <button class="btn secondary" type="button" data-retry>Try again</button></div>`;
    root.querySelector("[data-retry]")?.addEventListener("click", () => {
      root.innerHTML = `<div class="loading">Loading shop…</div>`;
      mountShopDetail(root, { shopId, standalone });
    });
    return false;
  }
}

async function renderListing(root, listingId) {
  root.innerHTML = `<div class="loading">Loading listing…</div>`;
  await mountListingDetail(root, { listingId });
}

// Share-link path for a listing (matches listingCard): /s/{shopSlug}/{productSlug}, or /d/ for delivery.
function sharePathFor(l, shop) {
  const shopSlug = shop?.shareSlug || normalizeShareSlug(shop?.name || "shop");
  const productSlug = l.shareSlug || normalizeShareSlug(l.title || l.id);
  const base = String(l.listingType) === "DELIVER" ? "/d/" : "/s/";
  return base + encodeURIComponent(shopSlug) + "/" + encodeURIComponent(productSlug);
}

// Mounts the Android-parity Listing Detail view into `root`.
// Used by the SPA (/listing/:id) and by the server-rendered /s/ and /d/ share pages (standalone).
async function mountListingDetail(root, { listingId, standalone = false } = {}) {
  try {
    const data = await fetchMarketplace();
    const listings = data.listings || [];
    const shops = data.shops || [];
    const l = listings.find(x => x.id === listingId);
    if (!l) {
      root.innerHTML = `<div class="error">Listing not found – it may have been removed.</div>`;
      return false;
    }
    const shopById = new Map(shops.map(s => [s.id, s]));
    const shop = shopById.get(l.shopId) || null;
    const isVisible = (x) => x.id !== l.id && x.isAvailable !== false;
    const moreFromShop = listings.filter(x => isVisible(x) && x.shopId === l.shopId).slice(0, 10);
    const moreIds = new Set(moreFromShop.map(x => x.id));
    const similar = l.category
      ? listings.filter(x => isVisible(x) && !moreIds.has(x.id) && x.category === l.category).slice(0, 10)
      : [];
    const ctx = {
      listing: l, shop, moreFromShop, similar, standalone,
      cardHTML: (x) => listingCard(x, shopById),
      shareUrl: location.origin + sharePathFor(l, shop)
    };
    root.innerHTML = renderListingDetailHTML(ctx);
    bindListingDetail(root, ctx, {
      addToCart,
      navigate: standalone ? (path) => location.assign(path) : navigate
    });
    if (!standalone && l.title) document.title = `${l.title} — SwiftShop`;
    return true;
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this listing. ${escapeHtml(err.message)} <button class="btn secondary" type="button" data-retry>Try again</button></div>`;
    root.querySelector("[data-retry]")?.addEventListener("click", () => {
      root.innerHTML = `<div class="loading">Loading listing…</div>`;
      mountListingDetail(root, { listingId, standalone });
    });
    return false;
  }
}

function renderCart(root) {
  const items = getCart();
  if (!items.length) {
    root.innerHTML = `<div class="empty">Your cart is empty. <a href="/">Browse listings</a></div>`;
    return;
  }
  root.innerHTML = `
    <h1>Your Cart</h1>
    <div class="cart-list">
      ${items.map(i => `
        <div class="cart-row" data-id="${i.listingId}">
          <span class="cart-title">${escapeHtml(i.title || i.listingId)}</span>
          <input type="number" min="0" value="${i.quantity}" class="qty-input" data-id="${i.listingId}">
          <button class="remove-btn" data-id="${i.listingId}">Remove</button>
        </div>`).join("")}
    </div>
    <button class="btn primary" id="checkoutBtn">Go to Checkout</button>`;
  root.querySelectorAll(".qty-input").forEach(input => {
    input.addEventListener("change", () => setQty(input.dataset.id, parseInt(input.value, 10) || 0) || renderCart(root));
  });
  root.querySelectorAll(".remove-btn").forEach(btn => {
    btn.addEventListener("click", () => { removeFromCart(btn.dataset.id); renderCart(root); });
  });
  root.querySelector("#checkoutBtn").addEventListener("click", () => navigate("/checkout"));
}

async function renderCheckout(root) {
  const items = getCart();
  if (!items.length) {
    root.innerHTML = `<div class="empty">Your cart is empty. <a href="/">Browse listings</a></div>`;
    return;
  }
  root.innerHTML = `<div class="loading">Calculating totals…</div>`;

  try {
    await ensureSignedIn();
    const calculatePurchaseTotal = httpsCallable(functions, "calculatePurchaseTotal");
    const { data: fees } = await calculatePurchaseTotal({
      items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title }))
    });

    root.innerHTML = `
      <h1>Checkout</h1>
      <div class="summary">
        <div>Subtotal: ${LSL(fees.subtotalMinorUnits || 0)}</div>
        <div>Platform fee: ${LSL(fees.platformFeeMinorUnits || 0)}</div>
        <div class="total">Total: ${LSL(fees.totalMinorUnits || 0)}</div>
      </div>
      <div class="payment-methods">
        <label><input type="radio" name="pm" value="SWIFT_WALLET" checked> Swift Wallet</label>
        <label class="disabled"><input type="radio" name="pm" value="MOPAY" disabled> Card / Mobile Money (coming soon on web)</label>
      </div>
      <button class="btn primary" id="placeOrderBtn">Place Order</button>
      <div id="checkoutMsg"></div>`;

    root.querySelector("#placeOrderBtn").addEventListener("click", async () => {
      const msg = root.querySelector("#checkoutMsg");
      msg.textContent = "Placing order…";
      try {
        const createPurchaseOrder = httpsCallable(functions, "createPurchaseOrder");
        const idempotencyKey = `web_${Date.now()}_${Math.random().toString(36).slice(2)}`;
        const { data: result } = await createPurchaseOrder({
          items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title })),
          paymentMethod: "SWIFT_WALLET",
          idempotencyKey
        });
        if (result.error) {
          msg.textContent = `Order failed: ${result.error}`;
          return;
        }
        localStorage.removeItem(CART_KEY);
        updateCartBadge();
        root.innerHTML = `
          <div class="success">
            <h1>Order placed 🎉</h1>
            <p>Order ID: ${escapeHtml(result.orderId)}</p>
            <a href="/">Continue browsing</a>
          </div>`;
      } catch (err) {
        msg.textContent = `Order failed: ${err.message}`;
      }
    });
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't prepare checkout. ${escapeHtml(err.message)}</div>`;
  }
}

// ---------- Search ----------

async function renderSearch(root, initialQuery = "") {
  root.innerHTML = `
    <section class="rail-page">
      <div class="section-heading"><span class="eyebrow">Swift search</span><h1>Search the Market</h1></div>
      <div class="search-panel">
        <input id="marketSearchInput" value="${escapeHtml(initialQuery)}" placeholder="Search products and shops..." aria-label="Search products and shops">
        <button class="btn primary" id="marketSearchBtn" type="button">Search</button>
      </div>
      <div id="searchResults"><div class="loading">Searching the market…</div></div>
    </section>`;
  const input = root.querySelector("#marketSearchInput");
  const results = root.querySelector("#searchResults");
  const run = async () => {
    const term = input.value.trim().toLowerCase();
    if (!term) { results.innerHTML = "<p class='empty'>Type a product or shop name to search.</p>"; return; }
    try {
      const [listings, shops] = await Promise.all([fetchListings({ max: 100 }), fetchShops({ max: 50 })]);
      const shopById = new Map(shops.map(shop => [shop.id, shop]));
      const matchedShops = shops.filter(s => String(s.name || "").toLowerCase().includes(term));
      const matchedListings = listings.filter(l =>
        String(l.title || "").toLowerCase().includes(term) ||
        String(l.description || "").toLowerCase().includes(term) ||
        String(shopById.get(l.shopId)?.name || "").toLowerCase().includes(term)
      );
      results.innerHTML = `
        <div class="search-group"><div class="section-heading"><h2>Shops</h2></div>
          <div class="shop-row">${matchedShops.length ? matchedShops.map(shopCard).join("") : "<p class='empty'>No matching shops.</p>"}</div>
        </div>
        <div class="search-group"><div class="section-heading"><h2>Products</h2></div>
          <div class="grid">${matchedListings.length ? matchedListings.map(l => listingCard(l, shopById)).join("") : "<p class='empty'>No matching products.</p>"}</div>
        </div>`;
    } catch (err) {
      results.innerHTML = `<div class="error">Couldn't search the market. ${escapeHtml(err.message)}</div>`;
    }
  };
  root.querySelector("#marketSearchBtn").addEventListener("click", run);
  input.addEventListener("keydown", e => { if (e.key === "Enter") run(); });
  if (initialQuery) run();
}

function formatOrderDate(value) {
  if (!value) return "Date unavailable";
  const date = value?.toDate ? value.toDate() : new Date(value);
  return Number.isNaN(date.getTime()) ? "Date unavailable" : date.toLocaleString();
}

function orderStatusLabel(value) {
  return String(value || "PENDING").replaceAll("_", " ").toLowerCase().replace(/(^|\\s)\\S/g, c => c.toUpperCase());
}

function orderCard(order) {
  const items = Array.isArray(order.items) ? order.items : [];
  const itemSummary = items.slice(0, 2).map(i => `${escapeHtml(i.title || "Item")} × ${Number(i.quantity || 1)}`).join(", ");
  const more = items.length > 2 ? ` + ${items.length - 2} more` : "";
  return `
    <a class="order-card" href="/orders/${encodeURIComponent(order.id)}">
      <div class="order-card-top"><strong>Order ${escapeHtml(order.id.slice(0, 8))}</strong><span>${escapeHtml(formatOrderDate(order.createdAt))}</span></div>
      <div class="order-items">${itemSummary || "Order items"}${more}</div>
      <div class="order-card-bottom"><span class="status-pill">${escapeHtml(orderStatusLabel(order.status))}</span><strong>${LSL(Number(order.totalMinorUnits || 0))}</strong></div>
    </a>`;
}

function subscribeBuyerOrders(userId, onData, onError) {
  const q = query(collection(db, "orders"), where("buyerId", "==", userId));
  return onSnapshot(q, snapshot => {
    const orders = snapshot.docs.map(d => ({ id: d.id, ...d.data() }))
      .sort((a, z) => {
        const at = a.createdAt?.toMillis ? a.createdAt.toMillis() : new Date(a.createdAt || 0).getTime();
        const zt = z.createdAt?.toMillis ? z.createdAt.toMillis() : new Date(z.createdAt || 0).getTime();
        return zt - at;
      });
    onData(orders);
  }, onError);
}

async function renderOrders(root) {
  root.innerHTML = `
    <section class="rail-page">
      <div class="section-heading"><span class="eyebrow">Swift account</span><h1>My Orders</h1></div>
      <div id="ordersState" class="loading">Checking your Swift account…</div>
    </section>`;
  const state = root.querySelector("#ordersState");
  const user = auth.currentUser;
  if (!user) {
    state.innerHTML = `<div class="empty"><h2>Sign in to see your orders</h2><p>Your orders are private to your Swift account.</p></div>`;
    return;
  }
  state.innerHTML = "<div class='loading'>Loading your orders…</div>";
  let unsubscribe;
  try {
    unsubscribe = subscribeBuyerOrders(user.uid, orders => {
      state.innerHTML = orders.length
        ? `<div class="orders-list">${orders.map(orderCard).join("")}</div>`
        : `<div class="empty"><h2>No orders yet</h2><p>When you buy something on Swift, it will appear here.</p><a class="btn primary" href="/market">Shop the Market</a></div>`;
    }, err => {
      state.innerHTML = `<div class="error">Couldn't load your orders. ${escapeHtml(err.message)}</div>`;
    });
  } catch (err) {
    state.innerHTML = `<div class="error">Couldn't load your orders. ${escapeHtml(err.message)}</div>`;
  }
  root._ordersUnsubscribe = unsubscribe;
}

async function renderOrderDetail(root, orderId) {
  root.innerHTML = "<div class='loading'>Loading order…</div>";
  const user = auth.currentUser;
  if (!user) {
    root.innerHTML = `<div class="empty"><h2>Sign in to view this order</h2><a class="btn primary" href="/orders">Back to Orders</a></div>`;
    return;
  }
  try {
    const snap = await getDoc(doc(db, "orders", orderId));
    if (!snap.exists()) {
      root.innerHTML = "<div class='error'>Order not found.</div>";
      return;
    }
    const order = { id: snap.id, ...snap.data() };
    if (order.buyerId !== user.uid && order.sellerId !== user.uid) {
      root.innerHTML = "<div class='error'>You do not have access to this order.</div>";
      return;
    }
    const items = Array.isArray(order.items) ? order.items : [];
    root.innerHTML = `
      <section class="rail-page order-detail">
        <a class="back-link" href="/orders">← Orders</a>
        <div class="section-heading"><span class="eyebrow">Order ${escapeHtml(order.id.slice(0, 8))}</span><h1>${escapeHtml(orderStatusLabel(order.status))}</h1></div>
        <div class="order-detail-grid">
          <div class="order-panel"><h2>Items</h2>${items.map(i => `<div class="order-line"><span>${escapeHtml(i.title || "Item")} × ${Number(i.quantity || 1)}</span><strong>${LSL(Number(i.unitPriceMinorUnits || 0) * Number(i.quantity || 1))}</strong></div>`).join("") || "<p>No items recorded.</p>"}</div>
          <div class="order-panel"><h2>Summary</h2>
            <div class="order-line"><span>Subtotal</span><span>${LSL(Number(order.subtotalMinorUnits || 0))}</span></div>
            <div class="order-line"><span>Platform fee</span><span>${LSL(Number(order.platformFeeMinorUnits || 0))}</span></div>
            <div class="order-line"><span>Delivery fee</span><span>${LSL(Number(order.deliveryFeeMinorUnits || 0))}</span></div>
            <div class="order-line total-line"><strong>Total</strong><strong>${LSL(Number(order.totalMinorUnits || 0))}</strong></div>
          </div>
          <div class="order-panel"><h2>Progress</h2>
            <p><strong>Payment:</strong> ${escapeHtml(order.paymentId ? "Recorded" : "Pending")}</p>
            <p><strong>Order:</strong> ${escapeHtml(orderStatusLabel(order.status))}</p>
            <p><strong>Inventory:</strong> ${escapeHtml(orderStatusLabel(order.inventoryStatus))}</p>
            <p><strong>Fulfillment:</strong> ${escapeHtml(orderStatusLabel(order.fulfillmentStatus))}</p>
            ${order.requiresDelivery ? "<p><strong>Delivery:</strong> Delivery requested/required for this order.</p>" : "<p><strong>Delivery:</strong> Pickup / no delivery requested.</p>"}
          </div>
        </div>
      </section>`;
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this order. ${escapeHtml(err.message)}</div>`;
  }
}


// ---------- Utility rail ----------

function renderRailCart() {
  const items = getCart();
  const count = cartCount();
  const badges = [document.getElementById("railCartBadge"), document.getElementById("railCartBadgeMobile")].filter(Boolean);
  const summaries = [document.getElementById("railCartSummary"), document.getElementById("railCartSummaryMobile")].filter(Boolean);
  const list = document.getElementById("railCartItems");
  const summaryText = count ? count + " item" + (count === 1 ? "" : "s") + " in your bag" : "Your bag is empty.";
  badges.forEach(el => { el.textContent = count ? String(count) : ""; });
  summaries.forEach(el => { el.textContent = summaryText; });
  if (list) {
    list.innerHTML = items.slice(0, 3).map(item =>
      '<div class="rail-cart-item">' + escapeHtml(item.title || item.listingId) + " × " + Number(item.quantity || 1) + "</div>"
    ).join("");
    if (items.length > 3) list.insertAdjacentHTML("beforeend", '<div class="rail-muted">+ ' + (items.length - 3) + ' more</div>');
  }
}

function renderCartDrawer() {
  const root = document.getElementById("cartDrawerContent");
  if (!root) return;
  const items = getCart();
  if (!items.length) {
    root.innerHTML = `<p class="rail-muted">Your bag is empty.</p><a class="rail-action" href="/market">Browse Market</a>`;
    return;
  }
  root.innerHTML = `
    <div class="cart-list">
      ${items.map(item => `
        <div class="cart-row">
          <span class="cart-title">${escapeHtml(item.title || item.listingId)}</span>
          <strong>× ${Number(item.quantity || 1)}</strong>
        </div>`).join("")}
    </div>
    <div class="actions"><a class="btn primary" href="/cart">View full bag</a><a class="btn secondary" href="/checkout">Checkout</a></div>`;
}

function openUtilitySheet() {
  const sheet = document.getElementById("utilitySheet");
  const trigger = document.querySelector("[data-open-utility]");
  if (!sheet) return;
  sheet.classList.add("is-open");
  sheet.setAttribute("aria-hidden", "false");
  if (trigger) trigger.setAttribute("aria-expanded", "true");
}

function closeUtilitySheet() {
  const sheet = document.getElementById("utilitySheet");
  const trigger = document.querySelector("[data-open-utility]");
  if (!sheet) return;
  sheet.classList.remove("is-open");
  sheet.setAttribute("aria-hidden", "true");
  if (trigger) trigger.setAttribute("aria-expanded", "false");
}

function openCartDrawer() {
  const drawer = document.getElementById("cartDrawer");
  if (!drawer) return;
  closeUtilitySheet();
  renderCartDrawer();
  drawer.classList.add("is-open");
  drawer.setAttribute("aria-hidden", "false");
}

function closeCartDrawer() {
  const drawer = document.getElementById("cartDrawer");
  if (!drawer) return;
  drawer.classList.remove("is-open");
  drawer.setAttribute("aria-hidden", "true");
}

// Bind the mobile More control directly as well as through the delegated handler below.
// This keeps the primary rail interaction reliable on mobile browsers after route/render changes.
function bindUtilityRailControls() {
  const moreButton = document.querySelector("[data-open-utility]");
  if (!moreButton || moreButton.dataset.utilityBound === "true") return;
  moreButton.dataset.utilityBound = "true";
  moreButton.addEventListener("click", event => {
    event.preventDefault();
    event.stopPropagation();
    openUtilitySheet();
  });
}



// ── renderHomeProfile ─────────────────────────────────────────────────────────
// Home screen for signed-in users: profile card with avatar upload, wallet,
// stats (followers, following, shops, listings), about, and market entry.
async function renderHomeProfile(root, user, marketPromise) {
  const uid = user.uid;
  const email = user.email || "";
  const [profSnap, walletSnap] = await Promise.all([
    getDoc(doc(db, "profiles", uid)).catch(() => null),
    getDoc(doc(db, "wallets", uid)).catch(() => null),
  ]);
  const profile = profSnap?.exists() ? profSnap.data() : {};
  const displayName = profile.displayName || user.displayName || user.email || "Swift shopper";
  const handle = email ? "@" + email.split("@")[0] : "";
  const initial = (displayName[0] || "S").toUpperCase();
  const avatarUrl = profile.avatarUrl || user.photoURL || null;
  const coverUrl = profile.coverUrl || null;

  const avatarImgHTML = (url) => url
    ? `<img class="hp-avatar" src="${escapeHtml(url)}" alt="${escapeHtml(displayName)}" id="hpAvatarImg">`
    : `<div class="hp-avatar hp-avatar-initials" id="hpAvatarImg">${escapeHtml(initial)}</div>`;

  document.body.classList.add("home-profile-route");
  root.innerHTML = `
    <section class="home-profile">
      <div class="hp-cover" id="hpCover">
        <img id="hpCoverImg" alt="Cover photo" style="display:none">
        <label class="hp-cover-edit" for="hpCoverInput">
          <span aria-hidden="true">🖼</span> Change cover
          <input type="file" id="hpCoverInput" accept="image/*" style="display:none">
        </label>
        <div class="hp-cover-spinner" id="hpCoverSpinner" style="display:none"></div>
      </div>

      <div class="hp-header">
        <div class="hp-avatar-wrap">
          ${avatarImgHTML(avatarUrl)}
          <label class="hp-avatar-edit" for="hpAvatarInput" title="Change photo" aria-label="Change profile photo">
            <span aria-hidden="true">📷</span>
            <input type="file" id="hpAvatarInput" accept="image/*" style="display:none">
          </label>
          <div class="hp-avatar-spinner" id="hpAvatarSpinner" style="display:none"></div>
        </div>
        <div class="hp-identity">
          <h2 class="hp-name">${escapeHtml(displayName)}</h2>
          <p class="hp-handle">${escapeHtml(handle)}</p>
          <p class="hp-email">${escapeHtml(email)}</p>\n          <p class="hp-location" id="hpLocation">${escapeHtml(profile.location || "")}</p>
        </div>
      </div>

      <div class="hp-stats" id="hpStats">
        <div class="hp-stat"><span class="hp-stat-val" id="hpFollowers">—</span><span class="hp-stat-label">Followers</span></div>
        <div class="hp-stat"><span class="hp-stat-val" id="hpFollowing">—</span><span class="hp-stat-label">Following</span></div>
        <div class="hp-stat"><span class="hp-stat-val" id="hpShops">—</span><span class="hp-stat-label">Shops</span></div>
        <div class="hp-stat"><span class="hp-stat-val" id="hpListings">—</span><span class="hp-stat-label">Listings</span></div>
      </div>

      <div class="hp-wallet" id="hpWallet">
        <div class="hp-wallet-inner">
          <span class="hp-wallet-label">Swift Wallet</span>
          <span class="hp-wallet-bal" id="hpWalletBal">M —</span>
        </div>
        <button class="hp-wallet-btn" type="button" data-hp-action="wallet">Manage</button>
      </div>

      <div class="hp-about-wrap" id="hpAboutWrap" style="display:none">
        <p class="hp-about" id="hpAbout"></p>
      </div>

      <div class="hp-actions">
        <button class="profile-action-btn primary" type="button" data-hp-action="market">Browse market</button>
        <button class="profile-action-btn" type="button" data-hp-action="edit">Edit profile</button>
      </div>

      <div class="hp-market-preview" id="hpMarketPreview">
        <div class="loading" style="padding:20px 0">Loading your market…</div>
      </div>
    </section>`;

  // Avatar upload
  const avatarInput = root.querySelector("#hpAvatarInput");
  const spinner     = root.querySelector("#hpAvatarSpinner");
  avatarInput?.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { alert("Photo must be under 5 MB."); return; }
    spinner.style.display = "flex";
    try {
      const ext      = file.name.split(".").pop() || "jpg";
      const path     = `media/${uid}/profile-avatar`;
      const ref      = storageRef(storage, path);
      await uploadBytes(ref, file, { contentType: file.type });
      const url      = await getDownloadURL(ref);
      const { updateProfile } = await import("https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js");
      await updateProfile(user, { photoURL: url });
      // Update Firestore profile
      try { await updateDoc(doc(db, "profiles", uid), { avatarUrl: url }); } catch(_) {}
      // Update avatar in DOM
      const imgWrap = root.querySelector("#hpAvatarImg");
      if (imgWrap) {
        const img = document.createElement("img");
        img.className = "hp-avatar"; img.id = "hpAvatarImg";
        img.src = url; img.alt = displayName; img.width = 96; img.height = 96;
        imgWrap.replaceWith(img);
      }
      // Update account cards in rail / sheet
      updateAccountCards({ ...user, photoURL: url });
    } catch (err) {
      alert("Couldn't upload photo. " + (err.message || ""));
    } finally {
      spinner.style.display = "none";
    }
  });

  // Cover photo upload
  const _coverImg     = root.querySelector("#hpCoverImg");
  const _coverSpinner = root.querySelector("#hpCoverSpinner");
  const _coverInput   = root.querySelector("#hpCoverInput");
  if (coverUrl && _coverImg) {
    _coverImg.src = coverUrl;
    _coverImg.style.display = "block";
  }
  _coverInput?.addEventListener("change", async e => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { alert("Cover photo must be under 5 MB."); return; }
    if (_coverSpinner) _coverSpinner.style.display = "flex";
    try {
      const ext  = file.name.split(".").pop() || "jpg";
      const sRef = storageRef(storage, `media/${uid}/profile-cover`);
      await uploadBytes(sRef, file, { contentType: file.type });
      const url  = await getDownloadURL(sRef);
      try { await updateDoc(doc(db, "profiles", uid), { coverUrl: url }); } catch(_) {}
      if (_coverImg) { _coverImg.src = url; _coverImg.style.display = "block"; }
    } catch (err) { alert("Couldn't upload cover. " + (err.message || "")); }
    finally { if (_coverSpinner) _coverSpinner.style.display = "none"; }
  });

  // Action buttons
  root.querySelectorAll("[data-hp-action]").forEach(btn => {
    btn.addEventListener("click", () => {
      const a = btn.dataset.hpAction;
      if (a === "market")  navigate("/market");
      if (a === "wallet")  navigate("/wallet");
      if (a === "edit")    showEditProfileModal(user, root);
    });
  });

  const setText = (id, val) => {
    const el = root.querySelector("#" + id);
    if (el) el.textContent = val ?? "0";
  };
  setText("hpFollowers", profile.followerCount ?? 0);
  setText("hpFollowing", profile.followingCount ?? 0);
  setText("hpShops", profile.shopCount ?? 0);
  setText("hpListings", profile.activeListingCount ?? 0);
  if (profile.bio) {
    const aboutEl = root.querySelector("#hpAbout");
    const wrapEl = root.querySelector("#hpAboutWrap");
    if (aboutEl) aboutEl.textContent = profile.bio;
    if (wrapEl) wrapEl.style.display = "";
  }
  if (profile.location) {
    const locationEl = root.querySelector("#hpLocation");
    if (locationEl) locationEl.textContent = profile.location;
  }
  if (walletSnap?.exists()) {
    const w = walletSnap.data();
    const bal = ((w.availableBalanceMinorUnits || 0) / 100).toFixed(2);
    const el = root.querySelector("#hpWalletBal");
    if (el) el.textContent = `M ${bal}`;
  }

  // Market preview beneath profile
  try {
    const [listings, shops] = await marketPromise;
    const preview = root.querySelector("#hpMarketPreview");
    if (!preview) return;
    const shopById = new Map(shops.map(s => [s.id, s]));
    const slice    = listings.slice(0, 6);
    preview.innerHTML = slice.length
      ? `<div class="section-heading" style="margin-top:8px"><span class="eyebrow">Fresh on Swift</span><h2>Latest listings</h2></div>
         <div class="grid">${slice.map(l => listingCard(l, shopById)).join("")}</div>
         <a class="section-link" href="/market" style="display:block;text-align:center;margin-top:12px">See all listings <span aria-hidden="true">→</span></a>`
      : `<p class="empty">No listings yet.</p>`;
  } catch (_) {
    const p = root.querySelector("#hpMarketPreview");
    if (p) p.innerHTML = "";
  }
}

// ── showEditProfileModal ──────────────────────────────────────────────────────
function showEditProfileModal(user, root) {
  const existing = document.getElementById("editProfileModal");
  if (existing) existing.remove();
  const modal = document.createElement("div");
  modal.id = "editProfileModal";
  modal.className = "ep-modal-backdrop";
  modal.innerHTML = `
    <div class="ep-modal" role="dialog" aria-modal="true" aria-label="Edit profile">
      <div class="ep-modal-header"><h3>Edit profile</h3><button class="ep-close" type="button" aria-label="Close">✕</button></div>
      <form class="signup-form ep-form" id="editProfileForm">
        <input type="text" id="epDisplayName" placeholder="Full name" autocomplete="name">
        <input type="text" id="epLocation" placeholder="Location" autocomplete="address-level2">
        <textarea id="epBio" placeholder="Bio" rows="3"></textarea>
        <p class="signup-form-error" id="epError" style="display:none"></p>
        <button class="btn primary" type="submit" id="epSaveBtn">Save changes</button>
      </form>
    </div>`;
  document.body.appendChild(modal);
  getDoc(doc(db, "profiles", user.uid)).then(snap => {
    const p = snap.exists() ? snap.data() : {};
    modal.querySelector("#epDisplayName").value = p.displayName || user.displayName || "";
    modal.querySelector("#epLocation").value = p.location || "";
    modal.querySelector("#epBio").value = p.bio || "";
  }).catch(() => { modal.querySelector("#epDisplayName").value = user.displayName || ""; });
  modal.querySelector(".ep-close")?.addEventListener("click", () => modal.remove());
  modal.addEventListener("click", e => { if (e.target === modal) modal.remove(); });
  modal.querySelector("#editProfileForm")?.addEventListener("submit", async e => {
    e.preventDefault();
    const btn = modal.querySelector("#epSaveBtn"), errEl = modal.querySelector("#epError");
    const newName = (modal.querySelector("#epDisplayName").value || "").trim();
    const newLocation = (modal.querySelector("#epLocation").value || "").trim();
    const newBio = (modal.querySelector("#epBio").value || "").trim();
    btn.disabled = true; btn.textContent = "Saving…"; if (errEl) errEl.style.display = "none";
    try {
      const { updateProfile } = await import("https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js");
      if (newName && newName !== user.displayName) await updateProfile(user, { displayName: newName });
      await updateDoc(doc(db, "profiles", user.uid), {
        displayName: newName || user.displayName || null,
        displayName_lowercase: (newName || user.displayName || "").toLowerCase(),
        location: newLocation || null,
        bio: newBio || null,
      });
      modal.remove();
      if (location.pathname === "/" || location.pathname === "") {
        await renderHomeProfile(document.getElementById("app"), auth.currentUser, Promise.all([fetchListings(), fetchShops()]));
      }
      updateAccountCards(auth.currentUser);
    } catch (err) {
      btn.disabled = false; btn.textContent = "Save changes";
      if (errEl) { errEl.textContent = err.message || "Couldn't save your profile."; errEl.style.display = "block"; }
    }
  });
}

function renderMarketFeed(root, listings, shops) {
  if (typeof frontDoorTimer !== "undefined" && frontDoorTimer) {
    clearInterval(frontDoorTimer); frontDoorTimer = null;
  }
  const shopById      = new Map(shops.map(s => [s.id, s]));
  const featuredShops = shops.slice(0, 6);
  root.innerHTML = `
    <section class="market-feed shops-preview">
      <div class="section-heading section-heading-row">
        <div><span class="eyebrow">Discover local businesses</span><h2>Shops</h2>
          <p class="section-subtitle">A few places to start. Explore all shops when you're ready.</p></div>
        <a class="section-link" href="/shops">More shops <span aria-hidden="true">→</span></a>
      </div>
      <div class="shop-row">${featuredShops.length ? featuredShops.map(shopCard).join("") : "<p class='empty'>No shops yet.</p>"}</div>
    </section>
    <section class="market-feed">
      <div class="section-heading"><span class="eyebrow">Fresh on Swift</span><h2>Latest listings</h2></div>
      <div class="grid">${listings.length ? listings.map(l => listingCard(l, shopById)).join("") : "<p class='empty'>No listings yet.</p>"}</div>
    </section>`;
}

// ─── updateAccountCards ───────────────────────────────────────────────────────
function updateAccountCards(user) {
  const guest       = !user || user.isAnonymous;
  const displayName = guest ? "Guest shopper" : (user.displayName || user.email || "Swift shopper");
  const handle      = (!guest && user.email) ? "@" + user.email.split("@")[0] : "";
  const initial     = (displayName[0] || "G").toUpperCase();
  const avatarUrl   = (!guest && user.photoURL) ? user.photoURL : null;

  const avatarHTML = avatarUrl
    ? `<img class="profile-avatar" src="${escapeHtml(avatarUrl)}" alt="${escapeHtml(displayName)}" width="48" height="48">`
    : `<div class="profile-avatar-initials">${escapeHtml(initial)}</div>`;

  const guestActions = `
    <button class="profile-action-btn primary" type="button" data-profile-action="signup">Create account</button>
    <button class="profile-action-btn" type="button" data-profile-action="signin">Sign in</button>`;
  const userActions = `
    <button class="profile-action-btn" type="button" data-profile-action="signout">Sign out</button>`;

  [
    ["railProfileAvatar", "profileSummary", "profileHandle", "railProfileActions"],
    ["sheetProfileAvatar", "profileSummaryMobile", "profileHandleMobile", "sheetProfileActions"],
  ].forEach(([avId, nameId, handleId, actionsId]) => {
    const avEl      = document.getElementById(avId);
    const nameEl    = document.getElementById(nameId);
    const handleEl  = document.getElementById(handleId);
    const actionsEl = document.getElementById(actionsId);
    if (avEl)      avEl.outerHTML      = avatarHTML;
    if (nameEl)    nameEl.textContent  = displayName;
    if (handleEl)  handleEl.textContent = handle;
    if (actionsEl) {
      actionsEl.innerHTML = guest ? guestActions : userActions;
      actionsEl.querySelectorAll("[data-profile-action]").forEach(btn => {
        btn.addEventListener("click", () => {
          const a = btn.dataset.profileAction;
          if (a === "signup" || a === "signin") navigate("/");
          if (a === "signout") {
            import("https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js")
              .then(({ signOut }) => signOut(auth).then(() => navigate("/"))).catch(() => {});
          }
        });
      });
    }
  });

  // Reflect updated auth state on the home profile screen if it's active
  if (!guest) {
    const onHome = location.pathname === "/" || location.pathname === "";
    const homeProfile = document.querySelector(".home-profile");
    if (onHome && homeProfile) {
      const nameEl   = homeProfile.querySelector(".hp-name");
      const handleEl = homeProfile.querySelector(".hp-handle");
      const emailEl  = homeProfile.querySelector(".hp-email");
      const imgEl    = homeProfile.querySelector("#hpAvatarImg");
      if (nameEl)   nameEl.textContent   = displayName;
      if (handleEl) handleEl.textContent = handle;
      if (emailEl)  emailEl.textContent  = (!guest && user.email) ? user.email : "";
      if (imgEl && user && user.photoURL) {
        const img = document.createElement("img");
        img.className = "hp-avatar"; img.id = "hpAvatarImg";
        img.src = user.photoURL; img.alt = displayName; img.width = 96; img.height = 96;
        imgEl.replaceWith(img);
      }
    }
  }
}

// ─── bindSignupForm ───────────────────────────────────────────────────────────
function bindGuestBtn(btn, marketPromise, root) {
  if (!btn) return;
  btn.addEventListener("click", async () => {
    btn.disabled = true;
    btn.textContent = "Loading market…";
    try {
      const [listings, shops] = await marketPromise;
      if (typeof frontDoorTimer !== "undefined" && frontDoorTimer) {
        clearInterval(frontDoorTimer); frontDoorTimer = null;
      }
      renderMarketFeed(root, listings, shops);
    } catch (_) {
      btn.disabled = false;
      btn.textContent = "Browse as guest →";
    }
  });
}

function bindSignupForm(root, marketPromise) {
  const form     = root.querySelector("#signupForm");
  const statusEl = root.querySelector("#marketReady");
  if (!form) return;

  // Switch to sign-in mode — swap form contents cleanly
  const switchToSigninMode = () => {
    form.dataset.mode = "signin";
    const titleEl    = root.querySelector("#guestEntryTitle");
    const subtitleEl = root.querySelector("#guestEntrySubtitle");
    if (titleEl)    titleEl.textContent    = "Welcome back";
    if (subtitleEl) subtitleEl.textContent = "Sign in to your Swift account.";
    form.innerHTML = `
      <input type="email" id="signupEmail" placeholder="Email address" autocomplete="email" required>
      <input type="password" id="signupPassword" placeholder="Password" autocomplete="current-password" required>
      <p class="signup-form-error" id="signupError" style="display:none"></p>
      <button class="btn primary guest-btn" type="submit" id="signupSubmitBtn">Sign in <span aria-hidden="true">→</span></button>
      <p class="signup-form-note">New to Swift? <button type="button" class="text-btn" id="switchToSignup">Create an account</button></p>
      <p class="signup-form-note" style="margin-top:4px"><button type="button" class="text-btn" id="browseGuestBtn2">Browse as guest →</button></p>`;
    form.querySelector("#switchToSignup")?.addEventListener("click", () => {
      form.dataset.mode = "signup";
      if (titleEl)    titleEl.textContent    = "Ready to shop?";
      if (subtitleEl) subtitleEl.textContent = "Create your account to buy, track orders and follow shops.";
      // Re-render signup fields without a full page reload
      form.innerHTML = `
        <div class="signup-form-row" id="nameRow">
          <input type="text" id="signupFirstName" placeholder="First name" autocomplete="given-name">
          <input type="text" id="signupLastName" placeholder="Last name" autocomplete="family-name">
        </div>
        <input type="email" id="signupEmail" placeholder="Email address" autocomplete="email" required>
        <input type="password" id="signupPassword" placeholder="Create a password" autocomplete="new-password" required>
        <textarea id="signupAbout" placeholder="About me (optional)" rows="2"></textarea>
        <p class="signup-form-error" id="signupError" style="display:none"></p>
        <button class="btn primary guest-btn" type="submit" id="signupSubmitBtn">Create account <span aria-hidden="true">→</span></button>
        <p class="signup-form-note">Already have Swift? <button type="button" class="text-btn" id="switchToSignin2">Sign in</button></p>
        <p class="signup-form-note" style="margin-top:4px"><button type="button" class="text-btn" id="browseGuestBtn3">Browse as guest →</button></p>`;
      form.querySelector("#switchToSignin2")?.addEventListener("click", switchToSigninMode);
      bindGuestBtn(form.querySelector("#browseGuestBtn3"), marketPromise, root);
    });
    bindGuestBtn(form.querySelector("#browseGuestBtn2"), marketPromise, root);
  };
  root.querySelector("#switchToSignin")?.addEventListener("click", switchToSigninMode);
  bindGuestBtn(root.querySelector("#browseGuestBtn"), marketPromise, root);

  form.addEventListener("submit", async e => {
    e.preventDefault();
    const mode     = form.dataset.mode || "signup";
    const emailEl  = form.querySelector("#signupEmail");
    const passEl   = form.querySelector("#signupPassword");
    const errorEl  = form.querySelector("#signupError");
    const submitEl = form.querySelector("#signupSubmitBtn");
    if (!emailEl || !passEl) return;

    const email    = emailEl.value.trim();
    const password = passEl.value;

    if (!email) { showFormError(errorEl, "Please enter your email address."); return; }
    if (!password) { showFormError(errorEl, "Please enter a password."); return; }
    if (mode === "signup" && password.length < 6) { showFormError(errorEl, "Password must be at least 6 characters."); return; }

    submitEl.disabled = true;
    submitEl.textContent = mode === "signin" ? "Signing in…" : "Creating account…";
    if (errorEl) errorEl.style.display = "none";

    try {
      const { createUserWithEmailAndPassword, signInWithEmailAndPassword, updateProfile }
        = await import("https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js");

      let user;
      if (mode === "signin") {
        ({ user } = await signInWithEmailAndPassword(auth, email, password));
      } else {
        const firstName = (form.querySelector("#signupFirstName")?.value || "").trim();
        const lastName  = (form.querySelector("#signupLastName")?.value  || "").trim();
        const fullName  = [firstName, lastName].filter(Boolean).join(" ") || email.split("@")[0];
        ({ user } = await createUserWithEmailAndPassword(auth, email, password));
        await updateProfile(user, { displayName: fullName });

      }

      updateAccountCards(user);
      if (statusEl) { statusEl.textContent = mode === "signin" ? "Welcome back!" : "Account created! Welcome to Swift."; }
      const [listings, shops] = await marketPromise;
      renderMarketFeed(root, listings, shops);

    } catch (err) {
      submitEl.disabled = false;
      submitEl.textContent = mode === "signin" ? "Sign in →" : "Create account →";
      showFormError(errorEl, friendlyAuthError(err.code));
    }
  });
}

function showFormError(el, msg) {
  if (!el) return;
  el.textContent = msg;
  el.style.display = "block";
}

function friendlyAuthError(code) {
  return ({
    "auth/email-already-in-use":   "An account with this email already exists. Try signing in.",
    "auth/invalid-email":          "That doesn't look like a valid email.",
    "auth/weak-password":          "Password must be at least 6 characters.",
    "auth/user-not-found":         "No account found with this email.",
    "auth/wrong-password":         "Incorrect password.",
    "auth/invalid-credential":     "Incorrect email or password.",
    "auth/too-many-requests":      "Too many attempts — please wait a moment.",
    "auth/network-request-failed": "Connection error. Check your network.",
  })[code] || "Something went wrong. Please try again.";
}

async function loadUtilityRail() {
  renderRailCart();
  onAuthStateChanged(auth, user => {
    updateAccountCards(user);
    if (location.pathname === "/" || location.pathname === "") route();
  });

  const providerTargets = [
    [document.getElementById("providerCount"), document.getElementById("providerList")],
    [document.getElementById("providerCountMobile"), document.getElementById("providerListMobile")]
  ].filter(([count, list]) => count || list);
  if (!providerTargets.length) return;
  providerTargets.forEach(([count]) => { if (count) count.textContent = "Checking availability…"; });
  try {
    await ensureSignedIn();
    const getDeliveryOptions = httpsCallable(functions, "getDeliveryOptions");
    const result = await getDeliveryOptions({});
    const options = Array.isArray(result.data?.options) ? result.data.options : [];
    providerTargets.forEach(([count, list]) => {
      if (count) count.textContent = options.length
        ? options.length + " active provider" + (options.length === 1 ? "" : "s")
        : "No active providers right now.";
      if (list) {
        list.innerHTML = options.slice(0, 3).map(option =>
          '<div class="delivery-option"><strong>' + escapeHtml(option.title || "Delivery provider") + '</strong><span>' +
          LSL(Number(option.priceMinorUnits || 0)) + ' · available</span></div>'
        ).join("") || "";
      }
    });
  } catch (error) {
    providerTargets.forEach(([count, list]) => {
      if (count) count.textContent = "Delivery availability is temporarily unavailable.";
      if (list) list.innerHTML = "";
    });
  }
}

document.addEventListener("click", event => {
  const openUtility = event.target.closest("[data-open-utility]");
  if (openUtility) {
    event.preventDefault();
    openUtilitySheet();
    return;
  }
  const closeUtility = event.target.closest("[data-close-utility]");
  if (closeUtility && !event.target.closest("[data-open-cart]")) {
    event.preventDefault();
    closeUtilitySheet();
    return;
  }
  const open = event.target.closest("[data-open-cart]");
  if (open) {
    event.preventDefault();
    openCartDrawer();
    return;
  }
  const close = event.target.closest("[data-close-cart]");
  if (close) {
    event.preventDefault();
    closeCartDrawer();
  }
});

document.addEventListener("keydown", event => {
  if (event.key === "Escape") {
    closeUtilitySheet();
    closeCartDrawer();
  }
});

document.addEventListener("swift:cart-updated", () => {
  renderRailCart();
  const drawer = document.getElementById("cartDrawer");
  if (drawer?.classList.contains("is-open")) renderCartDrawer();
});
window.addEventListener("storage", event => {
  if (event.key === CART_KEY) {
    renderRailCart();
    renderCartDrawer();
  }
});

// ---------- Router ----------

function navigate(path) {
  history.pushState({}, "", path);
  route();
}

window.navigatePath = navigate;
window.openCartDrawer = openCartDrawer;

function route() {
  const root = document.getElementById("app");
  if (!root) return;
  const path = location.pathname;
  document.body.classList.toggle("listing-detail-route", /^\/listing\/[^/]+\/?$/.test(path));
  updateCartBadge();
  document.querySelectorAll(".rail a").forEach(link => {
    const href = link.getAttribute("href");
    const active = href === "/" ? (path === "/" || path === "") : path === href || path.startsWith(href + "/");
    link.classList.toggle("is-active", active);
    if (active) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
  });

  // The marketing hero is static markup above #app: show it on Home/Market only.
  document.documentElement.classList.toggle("no-hero", !(path === "/" || path === "" || path === "/market" || path === "/market/"));
  document.body.classList.toggle("home-profile-route",
    (path === "/" || path === "") && !!(auth.currentUser && !auth.currentUser.isAnonymous));
  if (path === "/" || path === "") return renderBrowse(root);
  if (path === "/market") return renderBrowse(root, true);
  if (path === "/search") return renderSearch(root, new URLSearchParams(location.search).get("q") || "");
  if (path === "/orders") return renderOrders(root);
  if (path === "/shops") return renderShops(root);
  if (path === "/cart") return renderCart(root);
  if (path === "/checkout") return renderCheckout(root);

  let m = path.match(/^\/orders\/([^/]+)\/?$/);
  if (m) return renderOrderDetail(root, decodeURIComponent(m[1]));

  m = path.match(/^\/shop\/([^/]+)\/?$/);
  if (m) return renderShop(root, m[1]);

  m = path.match(/^\/listing\/([^/]+)\/?$/);
  if (m) return renderListing(root, m[1]);

  root.innerHTML = `<div class="error">Page not found. <a href="/">Go home</a></div>`;
}

// Paths served by Cloud Functions (smart links, payment pages, API). The client router has no
// route for them, so they must be real browser navigations, never intercepted.
const SERVER_ROUTED = /^\/(s|d|pay|api)(\/|\?|#|$)/;

document.addEventListener("click", (e) => {
  if (document.body.dataset.sharePage === "true") return; // server-rendered page: let links navigate normally
  const a = e.target.closest("a[href^='/']");
  if (!a) return;
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  if (a.target && a.target !== "_self") return;
  const href = a.getAttribute("href");
  if (SERVER_ROUTED.test(href)) return;
  e.preventDefault();
  navigate(href);
});
window.addEventListener("popstate", route);
// This module uses top-level await (Firebase config fetch), so DOMContentLoaded may already have fired
// by the time we get here; a bare DOMContentLoaded listener would then never run and the router would
// never start. Boot immediately if the document is already parsed.
function boot() {
  updateCartBadge();
  loadUtilityRail();
  bindUtilityRailControls();
  if ((location.pathname.startsWith("/s/") || location.pathname.startsWith("/d/"))
      && document.body.dataset.sharePage === "true") return;
  route();
}
if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
else boot();

export { addToCart, cartCount, mountListingDetail, mountShopDetail };
