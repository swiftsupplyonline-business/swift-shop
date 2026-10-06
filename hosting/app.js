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
let frontDoorTimer = null;

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

// ---------- Rail Data (Delivery & Profile) ----------

async function loadDeliveryProvidersRail() {
  const providerList = document.getElementById("providerList");
  const providerCount = document.getElementById("providerCount");
  if (!providerList) return;

  try {
    await ensureSignedIn();
    const getOptions = httpsCallable(functions, "getDeliveryOptions");
    const { data } = await getOptions();
    const options = data?.options || [];
    if (providerCount) providerCount.textContent = String(options.length);

    if (!options.length) {
      providerList.innerHTML = `<div class="sub">No active delivery providers in area.</div>`;
      return;
    }

    providerList.innerHTML = options.slice(0, 4).map(opt => `
      <div class="provider-item">
        <div class="provider-avatar">🚚</div>
        <div class="provider-info">
          <div class="provider-title">${escapeHtml(opt.title || "Delivery Partner")}</div>
          <div class="provider-meta">${LSL(opt.priceMinorUnits || 0)} · ${opt.deliveryEstimateDays || 1}d est.</div>
        </div>
      </div>`).join("");
  } catch (err) {
    if (providerList) providerList.innerHTML = `<div class="sub">Logistics options available at checkout</div>`;
  }
}

function updateProfileRail(user) {
  const summary = document.getElementById("profileSummary");
  if (!summary) return;
  if (user && !user.isAnonymous) {
    summary.innerHTML = `
      <div style="font-weight:800;font-size:14px;">${escapeHtml(user.displayName || user.email || "Member")}</div>
      <div class="sub">Verified Account</div>`;
  } else {
    summary.innerHTML = `
      <div style="font-weight:700;font-size:13px;margin-bottom:4px;">Guest shopper</div>
      <div class="sub">Order online, track via App</div>`;
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

function listingCard(l, shopById = new Map()) {
  const img = (l.imageUrls && l.imageUrls[0]) || "";
  const shop = shopById.get(l.shopId);
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
  return `
    <a class="shop-card" href="/shop/${s.id}">
      <div class="shop-card-cover" style="background-image:url('${s.coverUrl || ""}')"></div>
      <div class="shop-card-scrim"></div>
      <div class="shop-card-content">
        <div class="shop-logo" style="background-image:url('${s.logoUrl || ""}')"></div>
        <div class="shop-name">${escapeHtml(s.name || "Shop")}</div>
      </div>
    </a>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

// ---------- Views ----------

async function renderBrowse(root) {
  if (frontDoorTimer) {
    clearInterval(frontDoorTimer);
    frontDoorTimer = null;
  }

  const marketPromise = Promise.all([fetchListings(), fetchShops()]);

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

      <div class="guest-entry">
        <div>
          <span class="eyebrow">Swift marketplace</span>
          <h2>Ready to shop?</h2>
          <p>Browse the market as a guest. No account needed.</p>
        </div>
        <button class="btn primary guest-btn" id="continueGuestBtn" type="button">Continue as Guest <span aria-hidden="true">→</span></button>
        <div class="signin-note">Already have Swift? <button type="button" class="text-btn" id="signInBtn">Sign in</button></div>
        <div class="market-ready" id="marketReady" role="status" aria-live="polite">Preparing the market…</div>
      </div>
    </section>`;

  const slides = [...root.querySelectorAll("[data-house-slide]")];
  const dots = [...root.querySelectorAll("[data-house-dot]")];
  let active = 0;
  const showSlide = (index) => {
    active = index % slides.length;
    slides.forEach((slide, i) => slide.classList.toggle("is-active", i === active));
    dots.forEach((dot, i) => dot.classList.toggle("is-active", i === active));
  };
  frontDoorTimer = setInterval(() => showSlide(active + 1), 4200);

  const guestBtn = root.querySelector("#continueGuestBtn");
  const ready = root.querySelector("#marketReady");
  const signInBtn = root.querySelector("#signInBtn");

  signInBtn?.addEventListener("click", () => {
    ready.textContent = "Sign-in is coming soon. You can keep browsing as a guest.";
  });

  guestBtn?.addEventListener("click", async () => {
    guestBtn.disabled = true;
    guestBtn.textContent = "Opening market…";
    try {
      const [listings, shops] = await marketPromise;
      const shopById = new Map(shops.map(shop => [shop.id, shop]));
      if (frontDoorTimer) {
        clearInterval(frontDoorTimer);
        frontDoorTimer = null;
      }
      root.innerHTML = `
        <section class="market-feed">
          <div class="section-heading"><span class="eyebrow">Swift marketplace</span><h2>Shops</h2></div>
          <div class="shop-row">${shops.length ? shops.map(shopCard).join("") : "<p class='empty'>No shops yet.</p>"}</div>
        </section>
        <section class="market-feed">
          <div class="section-heading"><span class="eyebrow">Fresh on Swift</span><h2>Latest listings</h2></div>
          <div class="grid">${listings.length ? listings.map(l => listingCard(l, shopById)).join("") : "<p class='empty'>No listings yet.</p>"}</div>
        </section>`;
    } catch (err) {
      guestBtn.disabled = false;
      guestBtn.textContent = "Continue as Guest →";
      ready.textContent = `Couldn't open the market: ${err.message}`;
      ready.classList.add("error-text");
    }
  });

  marketPromise.then(() => {
    if (ready) ready.textContent = "Market ready — jump in whenever you're ready.";
  }).catch(() => {
    if (ready) ready.textContent = "The market is taking a moment. Try Continue as Guest again.";
  });
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
      root.innerHTML = `<div class="error">Listing not found – it may have been removed.</div>`;
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

  if (path === "/" || path === "" || path === "/market") return renderBrowse(root);
  if (path === "/cart") return renderCart(root);
  if (path === "/checkout") return renderCheckout(root);

  let m = path.match(/^\/shop\/([^/]+)\/?$/);
  if (m) return renderShop(root, m[1]);

  m = path.match(/^\/listing\/([^/]+)\/?$/);
  if (m) return renderListing(root, m[1]);

  root.innerHTML = `<div class="error">Page not found. <a href="/">Go home</a></div>`;
}

// Paths served by Cloud Functions (smart links, payment pages, API). The client router has no
// route for them, so they must be real browser navigations, never intercepted.
const SERVER_ROUTED = /^\/(s|d|pay|api)(\/|\?|#|$)/;

document.addEventListener("click", (e) => {
  const a = e.target.closest("a[href^='/']");
  if (!a) return;
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  if (a.target && a.target !== "_self") return;
  const href = a.getAttribute("href");
  if (SERVER_ROUTED.test(href)) return;
  e.preventDefault();
  navigate(href);
});
window.navigatePath = navigate;

window.addEventListener("popstate", route);
document.addEventListener("DOMContentLoaded", () => {
  updateCartBadge();
  ensureSignedIn().then(updateProfileRail).catch(() => updateProfileRail(null));
  loadDeliveryProvidersRail();

  window.openCartDrawer = () => {
    navigate("/cart");
  };

  if ((location.pathname.startsWith("/s/") || location.pathname.startsWith("/d/"))
      && document.body.dataset.sharePage === "true") return;
  route();
});

export { addToCart, cartCount };
