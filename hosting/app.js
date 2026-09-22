// SwiftShop Web — real Firestore-backed marketplace client.
// Loaded as a module by index.html (browse / shop / cart / checkout routes)
// and, in "widget" mode, by the server-rendered /listing/{id} page for the
// Add to Cart / Buy Now buttons.
//
// NOTE: this file only ever calls existing Cloud Functions (calculateOrderFees,
// createOrder) — it does not implement any order/payment/inventory logic itself.
// That authority stays server-side in functions/src/commerce.ts.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInAnonymously
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js";
import {
  getFunctions, httpsCallable
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-functions.js";

const firebaseConfig = {
  apiKey: "AIzaSyBio3_Gk8CH5FUbeL1x9bWQJlslm3snU7o",
  authDomain: "swift-d1baa.firebaseapp.com",
  projectId: "swift-d1baa",
  storageBucket: "swift-d1baa.firebasestorage.app",
  messagingSenderId: "638577051798",
  appId: "1:638577051798:web:032a399efc355526eb9ab7",
  measurementId: "G-HC3S8PH6L4"
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const functions = getFunctions(app);

const CART_KEY = "swiftshop_cart_v1";
const LSL = (minorUnits) => `M${(minorUnits / 100).toFixed(2)}`;

// ---------- Cart (client-side convenience only — never authoritative) ----------

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
    el.style.display = n > 0 ? "inline-block" : "none";
  });
}

// ---------- Auth (anonymous — enough to call authenticated callables) ----------

function ensureSignedIn() {
  return new Promise((resolve, reject) => {
    const unsub = onAuthStateChanged(auth, (user) => {
      unsub();
      if (user) return resolve(user);
      signInAnonymously(auth).then(cred => resolve(cred.user)).catch(reject);
    });
  });
}

// ---------- Data (server-backed public marketplace API) ----------
// The browser deliberately does not read Firestore directly. The public
// marketplace Cloud Function uses the Admin SDK against the same canonical
// collections written by the Kotlin app. This keeps the web client independent
// of Firestore security-rule state and prevents a second web catalogue.

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

async function fetchShops({ max = 12 } = {}) {
  const data = await fetchMarketplace();
  return (data.shops || []).slice(0, max);
}

async function fetchShop(shopId) {
  const data = await fetchMarketplace();
  return (data.shops || []).find(s => s.id === shopId) || null;
}

async function fetchShopListings(shopId, { max = 48 } = {}) {
  const data = await fetchMarketplace();
  return (data.listings || []).filter(l => l.shopId === shopId).slice(0, max);
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

function listingCard(l) {
  const img = (l.imageUrls && l.imageUrls[0]) || "";
  return `
    <a class="card" href="/listing/${l.id}">
      <div class="card-img" style="background-image:url('${img}')"></div>
      <div class="card-body">
        <div class="card-title">${escapeHtml(l.title || "Untitled")}</div>
        <div class="card-price">${LSL(l.priceMinorUnits || 0)}</div>
      </div>
    </a>`;
}

function shopCard(s) {
  return `
    <a class="shop-card" href="/shop/${s.id}">
      <div class="shop-logo" style="background-image:url('${s.logoUrl || ""}')"></div>
      <div class="shop-name">${escapeHtml(s.name || "Shop")}</div>
    </a>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

// ---------- Views ----------

async function renderBrowse(root) {
  root.innerHTML = `<div class="loading">Loading marketplace…</div>`;
  try {
    const [listings, shops] = await Promise.all([fetchListings(), fetchShops()]);
    root.innerHTML = `
      <section>
        <h2>Shops</h2>
        <div class="shop-row">${shops.length ? shops.map(shopCard).join("") : "<p class='empty'>No shops yet.</p>"}</div>
      </section>
      <section>
        <h2>Latest listings</h2>
        <div class="grid">${listings.length ? listings.map(listingCard).join("") : "<p class='empty'>No listings yet.</p>"}</div>
      </section>`;
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
      <div class="shop-header">
        <div class="shop-cover" style="background-image:url('${shop.coverUrl || ""}')"></div>
        <h1>${escapeHtml(shop.name || "Shop")}</h1>
        <p>${escapeHtml(shop.description || "")}</p>
      </div>
      <div class="grid">${listings.length ? listings.map(listingCard).join("") : "<p class='empty'>No listings yet.</p>"}</div>`;
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this shop. ${escapeHtml(err.message)}</div>`;
  }
}

async function renderListing(root, listingId) {
  root.innerHTML = `<div class="loading">Loading listing…</div>`;
  try {
    const l = await fetchListing(listingId);
    if (!l) {
      root.innerHTML = `<div class="error">Listing not found — it may have been removed.</div>`;
      return;
    }
    const img = (l.imageUrls && l.imageUrls[0]) || "";
    root.innerHTML = `
      <div class="listing-detail">
        <div class="listing-img" style="background-image:url('${img}')"></div>
        <h1>${escapeHtml(l.title || "Untitled")}</h1>
        <div class="price">${LSL(l.priceMinorUnits || 0)}</div>
        <p>${escapeHtml(l.description || "")}</p>
        <p class="stock">${(l.stockQuantity || 0) > 0 ? `In stock: ${l.stockQuantity}` : "Out of stock"}</p>
        <a class="shop-link" href="/shop/${l.shopId}">Visit shop</a>
        <div class="actions">
          <button class="btn secondary" id="addCartBtn" ${(l.stockQuantity || 0) <= 0 ? "disabled" : ""}>Add to Cart</button>
          <button class="btn primary" id="buyNowBtn" ${(l.stockQuantity || 0) <= 0 ? "disabled" : ""}>Buy Now</button>
        </div>
      </div>`;
    root.querySelector("#addCartBtn")?.addEventListener("click", () => {
      addToCart(l.id, l.title, 1);
      root.querySelector("#addCartBtn").textContent = "Added ✓";
    });
    root.querySelector("#buyNowBtn")?.addEventListener("click", () => {
      addToCart(l.id, l.title, 1);
      navigate("/checkout");
    });
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this listing. ${escapeHtml(err.message)}</div>`;
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
    const calculateOrderFees = httpsCallable(functions, "calculateOrderFees");
    const { data: fees } = await calculateOrderFees({
      items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title })),
      requiresDelivery: false
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
  const a = e.target.closest("a[href^='/']");
  if (!a) return;
  e.preventDefault();
  navigate(a.getAttribute("href"));
});
window.addEventListener("popstate", route);
document.addEventListener("DOMContentLoaded", () => { updateCartBadge(); route(); });

export { addToCart, cartCount };
