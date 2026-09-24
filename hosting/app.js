// SwiftShop Web – real Firestore-backed marketplace client.
// Loaded as a module by index.html (browse / shop / cart / checkout routes)
// and, in "widget" mode, by the server-rendered /listing/{id} page for the
// Add to Cart / Buy Now buttons.
//
// NOTE: this file only ever calls existing Cloud Functions (calculateOrderFees,
// createOrder) – it does not implement any order/payment/inventory logic itself.
// That authority stays server-side in functions/src/commerce.ts.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInAnonymously
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js";
import {
  getFunctions, httpsCallable
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-functions.js";

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

const CART_KEY = "swiftshop_cart_v1";
const LSL = (minorUnits) => `M${(minorUnits / 100).toFixed(2)}`;

// ---------- Cart (client-side convenience only – never authoritative) ----------

function getCart() {
  try { return JSON.parse(localStorage.getItem(CART_KEY)) || []; }
  catch { return []; }
}
function saveCart(items) {
  localStorage.setItem(CART_KEY, JSON.stringify(items));
  updateCartBadge();
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
    el.style.display = n > 0 ? "inline-flex" : "none";
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

async function fetchListings({ max = 60 } = {}) {
  const data = await fetchMarketplace();
  return (data.listings || []).slice(0, max);
}

async function fetchShops({ max = 30 } = {}) {
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

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

function toast(t) {
  const e = document.getElementById("toast");
  if (!e) return;
  e.textContent = t;
  e.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => e.classList.remove("show"), 2200);
}

function listingCard(l) {
  const img = (l.imageUrls && l.imageUrls[0]) || "";
  return `
    <a class="product" href="/listing/${l.id}">
      <div class="photo" style="background-image:url('${img}')">
        <span class="tag">${escapeHtml(l.category || "General")}</span>
        <button type="button" class="heart" data-fav>♡</button>
      </div>
      <div class="info">
        <div class="p-shop">${escapeHtml(l.shopName || "")}</div>
        <div class="p-name">${escapeHtml(l.title || "Untitled")}</div>
        <div class="p-price">${LSL(l.priceMinorUnits || 0)}</div>
      </div>
    </a>`;
}

function shopCard(s) {
  return `
    <a class="shop-card" href="/shop/${s.id}">
      <div class="shop-avatar" style="background-image:url('${s.logoUrl || ""}')"></div>
      <h3>${escapeHtml(s.name || "Shop")}</h3>
      <p>${escapeHtml(s.category || "")}</p>
    </a>`;
}

// ---------- Views ----------

// Cache of the currently-loaded browse data, so the header search box and
// category chips can filter in place without refetching /api/marketplace.
let browseCache = { listings: [], shops: [] };

const CATEGORIES = ["Beauty", "Fashion", "Gadgets", "Home", "Baby", "Food"];

function renderGridInto(container, listings) {
  if (!container) return;
  container.innerHTML = listings.length
    ? listings.map(listingCard).join("")
    : `<p class="empty" style="grid-column:1/-1">No listings match yet — check back soon.</p>`;
}

function filterBrowse(query) {
  const grid = document.getElementById("grid");
  if (!grid) return; // not on the browse route
  const q = (query || "").toLowerCase();
  const filtered = q
    ? browseCache.listings.filter(l =>
        `${l.title} ${l.category} ${l.shopName || ""}`.toLowerCase().includes(q))
    : browseCache.listings;
  renderGridInto(grid, filtered);

  document.querySelectorAll(".categories .cat").forEach(btn => {
    btn.classList.toggle("active", btn.dataset.cat.toLowerCase() === q);
  });
}

async function renderBrowse(root) {
  const shopsById = {}; // populated once shops load, used to label cards with shop names

  root.innerHTML = `
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow">Swift marketplace · Lesotho</div>
        <h1>Local finds.<br>Delivered fast.</h1>
        <p>Discover products from local shops, share a listing with one smart link, and buy directly from your phone.</p>
        <a class="cta" href="#products-section">Explore products ↗</a>
      </div>
    </section>
    <section class="section">
      <div class="section-head"><div><h2>Shop by category</h2><div class="sub">Everything you need, from shops near you.</div></div></div>
      <div class="categories">
        <button type="button" class="cat" data-cat="">All</button>
        ${CATEGORIES.map(c => `<button type="button" class="cat" data-cat="${c}">${c}</button>`).join("")}
      </div>
    </section>
    <section class="section" id="products-section">
      <div class="section-head"><div><h2>Trending on Swift</h2><div class="sub">Live listings from real shops.</div></div></div>
      <div class="grid" id="grid"><p class="loading" style="grid-column:1/-1">Loading listings…</p></div>
    </section>
    <section class="section">
      <div class="section-head"><div><h2>Local shops</h2><div class="sub">Meet the people behind the products.</div></div></div>
      <div class="shops" id="shopsGrid"><p class="loading">Loading shops…</p></div>
    </section>`;

  try {
    const [listings, shops] = await Promise.all([fetchListings(), fetchShops()]);
    shops.forEach(s => { shopsById[s.id] = s; });
    listings.forEach(l => { l.shopName = shopsById[l.shopId]?.name || ""; });
    browseCache = { listings, shops };

    renderGridInto(document.getElementById("grid"), listings);

    const shopsGrid = document.getElementById("shopsGrid");
    shopsGrid.innerHTML = shops.length
      ? shops.slice(0, 3).map(shopCard).join("")
      : `<p class="empty">No shops live yet.</p>`;

    filterBrowse(document.getElementById("search")?.value || "");
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load the marketplace. ${escapeHtml(err.message)}</div>`;
  }
}

async function renderShop(root, shopId) {
  root.innerHTML = `<div class="loading">Loading shop…</div>`;
  try {
    const [shop, listings] = await Promise.all([fetchShop(shopId), fetchShopListings(shopId)]);
    if (!shop) {
      root.innerHTML = `<div class="error">Shop not found.</div>`;
      return;
    }
    root.innerHTML = `
      <section class="section" style="margin-top:20px">
        <div class="shop-card" style="min-height:120px;margin-bottom:24px">
          <div class="shop-avatar" style="background-image:url('${shop.logoUrl || ""}')"></div>
          <h3>${escapeHtml(shop.name || "Shop")}</h3>
          <p>${escapeHtml(shop.description || "")}</p>
        </div>
        <div class="grid">${listings.length ? listings.map(listingCard).join("") : "<p class='empty' style='grid-column:1/-1'>No listings yet.</p>"}</div>
      </section>`;
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this shop. ${escapeHtml(err.message)}</div>`;
  }
}

async function renderListing(root, listingId) {
  root.innerHTML = `<div class="loading">Loading listing…</div>`;
  try {
    const l = await fetchListing(listingId);
    if (!l) {
      root.innerHTML = `<div class="error">Listing not found – it may have been removed.</div>`;
      return;
    }
    const img = (l.imageUrls && l.imageUrls[0]) || "";
    root.innerHTML = `
      <div class="detail">
        <div class="detail-photo" style="background-image:url('${img}')"></div>
        <div>
          <div class="eyebrow">${escapeHtml(l.category || "General")}</div>
          <h1>${escapeHtml(l.title || "Untitled")}</h1>
          <div class="price">${LSL(l.priceMinorUnits || 0)}</div>
          <p>${escapeHtml(l.description || "")}</p>
          <p class="stock">${(l.stockQuantity || 0) > 0 ? `In stock: ${l.stockQuantity}` : "Out of stock"}</p>
          <a class="shop-link" href="/shop/${l.shopId}">Visit shop</a>
          <div class="actions">
            <button class="btn secondary" id="addCartBtn" ${(l.stockQuantity || 0) <= 0 ? "disabled" : ""}>Add to Cart</button>
            <button class="btn primary" id="buyNowBtn" ${(l.stockQuantity || 0) <= 0 ? "disabled" : ""}>Buy Now</button>
          </div>
          <button type="button" class="pill" style="margin-top:14px" id="shareBtn">↗ Share smart link</button>
        </div>
      </div>`;
    root.querySelector("#addCartBtn")?.addEventListener("click", () => {
      addToCart(l.id, l.title, 1);
      root.querySelector("#addCartBtn").textContent = "Added ✓";
      toast(`${l.title} added to your bag`);
    });
    root.querySelector("#buyNowBtn")?.addEventListener("click", () => {
      addToCart(l.id, l.title, 1);
      navigate("/checkout");
    });
    root.querySelector("#shareBtn")?.addEventListener("click", () => {
      const url = `${location.origin}/listing/${l.id}`;
      navigator.clipboard?.writeText(url).then(() => toast("Smart link copied")).catch(() => toast(url));
    });
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this listing. ${escapeHtml(err.message)}</div>`;
  }
}

function renderCart(root) {
  const items = getCart();
  if (!items.length) {
    root.innerHTML = `<div class="empty">Your cart is empty. <a class="cta" href="/">Browse listings</a></div>`;
    return;
  }
  root.innerHTML = `
    <h1>Your Bag</h1>
    <div class="cart-list">
      ${items.map(i => `
        <div class="cart-row" data-id="${i.listingId}">
          <span class="cart-title">${escapeHtml(i.title || i.listingId)}</span>
          <input type="number" min="0" value="${i.quantity}" class="qty-input" data-id="${i.listingId}">
          <button class="remove-btn" data-id="${i.listingId}">Remove</button>
        </div>`).join("")}
    </div>
    <button class="btn primary" id="checkoutBtn">Continue to checkout</button>`;
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
    root.innerHTML = `<div class="empty">Your cart is empty. <a class="cta" href="/">Browse listings</a></div>`;
    return;
  }
  root.innerHTML = `<div class="loading">Calculating totals…</div>`;

  try {
    await ensureSignedIn();
    const calculateOrderFees = httpsCallable(functions, "calculateOrderFees");
    const { data: fees } = await calculateOrderFees({
      items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title })),
      requiresDelivery: false
    });

    root.innerHTML = `
      <h1>Checkout</h1>
      <div class="summary">
        <div class="row"><span>Subtotal</span><span>${LSL(fees.subtotalMinorUnits || 0)}</span></div>
        <div class="row"><span>Platform fee</span><span>${LSL(fees.platformFeeMinorUnits || 0)}</span></div>
        <div class="row total"><span>Total</span><span>${LSL(fees.totalMinorUnits || 0)}</span></div>
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
        const createOrder = httpsCallable(functions, "createOrder");
        const idempotencyKey = `web_${Date.now()}_${Math.random().toString(36).slice(2)}`;
        const { data: result } = await createOrder({
          items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title })),
          requiresDelivery: false,
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
            <a class="cta" href="/">Continue browsing</a>
          </div>`;
      } catch (err) {
        msg.textContent = `Order failed: ${err.message}`;
      }
    });
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't prepare checkout. ${escapeHtml(err.message)}</div>`;
  }
}

// ---------- Router ----------

function navigate(path) {
  history.pushState({}, "", path);
  route();
}

function route() {
  const root = document.getElementById("app");
  if (!root) return;
  const path = location.pathname;
  updateCartBadge();

  if (path === "/" || path === "") return renderBrowse(root);
  if (path === "/cart") return renderCart(root);
  if (path === "/checkout") return renderCheckout(root);

  let m = path.match(/^\/shop\/([^/]+)\/?$/);
  if (m) return renderShop(root, m[1]);

  m = path.match(/^\/listing\/([^/]+)\/?$/);
  if (m) return renderListing(root, m[1]);

  root.innerHTML = `<div class="error">Page not found. <a href="/">Go home</a></div>`;
}

document.addEventListener("click", (e) => {
  const favBtn = e.target.closest("[data-fav]");
  if (favBtn) { e.preventDefault(); e.stopPropagation(); toast("Saved to favourites"); return; }

  const catBtn = e.target.closest(".categories .cat");
  if (catBtn) { filterBrowse(catBtn.dataset.cat); return; }

  const a = e.target.closest("a[href^='/']");
  if (!a) return;
  e.preventDefault();
  navigate(a.getAttribute("href"));
});

window.addEventListener("popstate", route);

document.addEventListener("DOMContentLoaded", () => {
  updateCartBadge();
  route();
  document.getElementById("search")?.addEventListener("input", (e) => {
    if (location.pathname !== "/") navigate("/");
    filterBrowse(e.target.value);
  });
});

export { addToCart, cartCount };