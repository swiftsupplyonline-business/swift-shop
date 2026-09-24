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

// Firebase Hosting exposes the configuration for the project serving this page.
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

let searchQuery = "";

// ---------- Cart ----------

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

// ---------- Auth ----------

function ensureSignedIn() {
  return new Promise((resolve, reject) => {
    const unsub = onAuthStateChanged(auth, (user) => {
      unsub();
      if (user) return resolve(user);
      signInAnonymously(auth).then(cred => resolve(cred.user)).catch(reject);
    });
  });
}

// ---------- Data ----------

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
  let list = data.listings || [];
  if (searchQuery.trim()) {
    const q = searchQuery.toLowerCase();
    list = list.filter(l => (l.title || "").toLowerCase().includes(q) || (l.description || "").toLowerCase().includes(q) || (l.category || "").toLowerCase().includes(q));
  }
  return list.slice(0, max);
}

async function fetchShops({ max = 12 } = {}) {
  const data = await fetchMarketplace();
  let shops = data.shops || [];
  if (searchQuery.trim()) {
    const q = searchQuery.toLowerCase();
    shops = shops.filter(s => (s.name || "").toLowerCase().includes(q) || (s.description || "").toLowerCase().includes(q));
  }
  return shops.slice(0, max);
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
  const found = (data.listings || []).find(l => l.id === listingId);
  if (found) return found;

  // Direct fallback query for individual listing smart links outside top 100
  try {
    const resp = await fetch(`/listing/${listingId}?json=true`, { headers: { "Accept": "application/json" } });
    if (resp.ok) {
      const item = await resp.json().catch(() => null);
      if (item && item.id) return item;
    }
  } catch (e) {
    // Ignore fallback errors
  }
  return null;
}

// ---------- Rendering helpers ----------

function listingCard(l) {
  const img = (l.imageUrls && l.imageUrls[0]) || "";
  return `
    <a class="card" href="/listing/${l.id}">
      <div class="card-img" style="background-image:url('${escapeHtml(img)}')"></div>
      <div class="card-body">
        <div class="card-title">${escapeHtml(l.title || "Untitled")}</div>
        <div class="card-shop">Category: ${escapeHtml(l.category || "General")}</div>
        <div class="card-price">${LSL(l.priceMinorUnits || 0)}</div>
      </div>
    </a>`;
}

function shopCard(s) {
  return `
    <a class="shop-card" href="/shop/${s.id}">
      <div class="shop-logo" style="background-image:url('${escapeHtml(s.logoUrl || "")}')"></div>
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
  root.innerHTML = `
    <section class="hero">
      <h1>Local finds. Delivered fast.</h1>
      <p>Discover verified products from local shops across Lesotho, share listings with smart links, and order online.</p>
    </section>
    <div class="loading">Loading marketplace…</div>`;
  try {
    const [listings, shops] = await Promise.all([fetchListings(), fetchShops()]);
    root.innerHTML = `
      <section class="hero">
        <h1>Local finds. Delivered fast.</h1>
        <p>Discover verified products from local shops across Lesotho, share listings with smart links, and order online.</p>
      </section>
      <section>
        <h2>Featured Shops</h2>
        <div class="shop-row">${shops.length ? shops.map(shopCard).join("") : "<p class='empty'>No matching shops found.</p>"}</div>
      </section>
      <section>
        <h2>${searchQuery ? `Search Results for "${escapeHtml(searchQuery)}"` : "Latest Listings"}</h2>
        <div class="grid">${listings.length ? listings.map(listingCard).join("") : "<p class='empty'>No matching listings found.</p>"}</div>
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
      root.innerHTML = `<div class="error">Shop not found. <a href="/">Return to Marketplace</a></div>`;
      return;
    }
    root.innerHTML = `
      <div class="shop-header">
        <div class="shop-cover" style="background-image:url('${escapeHtml(shop.coverUrl || "")}')"></div>
        <h1>${escapeHtml(shop.name || "Shop")}</h1>
        <p>${escapeHtml(shop.description || "Welcome to " + (shop.name || "our shop"))}</p>
      </div>
      <h2>Shop Listings</h2>
      <div class="grid">${listings.length ? listings.map(listingCard).join("") : "<p class='empty'>No listings available in this shop.</p>"}</div>`;
  } catch (err) {
    root.innerHTML = `<div class="error">Couldn't load this shop. ${escapeHtml(err.message)}</div>`;
  }
}

async function renderListing(root, listingId) {
  root.innerHTML = `<div class="loading">Loading product details…</div>`;
  try {
    const l = await fetchListing(listingId);
    if (!l) {
      root.innerHTML = `<div class="error">Listing not found — it may have been removed or is no longer available. <a href="/">Return home</a></div>`;
      return;
    }
    const img = (l.imageUrls && l.imageUrls[0]) || "";
    root.innerHTML = `
      <div class="listing-detail">
        <div class="listing-img" style="background-image:url('${escapeHtml(img)}')"></div>
        <h1>${escapeHtml(l.title || "Untitled")}</h1>
        <div class="price">${LSL(l.priceMinorUnits || 0)}</div>
        <p>${escapeHtml(l.description || "No description provided.")}</p>
        <p class="stock"><strong>Availability:</strong> ${(l.stockQuantity || 0) > 0 ? `In stock (${l.stockQuantity} remaining)` : "<span style='color:#ef4444'>Out of stock</span>"}</p>
        <p><a class="shop-link" href="/shop/${l.shopId}">Visit Shop →</a></p>
        <div class="actions">
          <button class="btn secondary" id="addCartBtn" ${(l.stockQuantity || 0) <= 0 ? "disabled" : ""}>Add to Cart</button>
          <button class="btn primary" id="buyNowBtn" ${(l.stockQuantity || 0) <= 0 ? "disabled" : ""}>Buy Now</button>
        </div>
      </div>`;
    root.querySelector("#addCartBtn")?.addEventListener("click", () => {
      addToCart(l.id, l.title, 1);
      const b = root.querySelector("#addCartBtn");
      if (b) { b.textContent = "Added to Cart ✓"; b.style.background = "#dbeafe"; }
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
    root.innerHTML = `<div class="empty">
      <h2>Your Cart is Empty</h2>
      <p>Explore our local marketplace to discover verified products.</p>
      <a class="btn primary" href="/" style="max-width:200px; margin-top:16px;">Browse Listings</a>
    </div>`;
    return;
  }
  root.innerHTML = `
    <h1>Your Cart (${cartCount()} items)</h1>
    <div class="summary" style="margin-bottom:20px;">
      <div class="cart-list">
        ${items.map(i => `
          <div class="cart-row" data-id="${i.listingId}">
            <span class="cart-title">${escapeHtml(i.title || i.listingId)}</span>
            <input type="number" min="1" value="${i.quantity}" class="qty-input" data-id="${i.listingId}">
            <button class="remove-btn" data-id="${i.listingId}">Remove</button>
          </div>`).join("")}
      </div>
      <div style="display:flex; gap:12px; margin-top:16px;">
        <a class="btn secondary" href="/">Continue Shopping</a>
        <button class="btn primary" id="checkoutBtn">Proceed to Checkout →</button>
      </div>
    </div>`;
  root.querySelectorAll(".qty-input").forEach(input => {
    input.addEventListener("change", () => {
      const q = parseInt(input.value, 10);
      if (q <= 0) removeFromCart(input.dataset.id);
      else setQty(input.dataset.id, q);
      renderCart(root);
    });
  });
  root.querySelectorAll(".remove-btn").forEach(btn => {
    btn.addEventListener("click", () => { removeFromCart(btn.dataset.id); renderCart(root); });
  });
  root.querySelector("#checkoutBtn")?.addEventListener("click", () => navigate("/checkout"));
}

async function renderCheckout(root) {
  const items = getCart();
  if (!items.length) {
    root.innerHTML = `<div class="empty">Your cart is empty. <a href="/">Browse listings</a></div>`;
    return;
  }

  let requiresDelivery = false;
  let deliveryAddress = { street: "", city: "Maseru" };

  async function updateCheckoutSummary() {
    const summaryEl = root.querySelector("#checkoutSummary");
    if (summaryEl) summaryEl.innerHTML = `<div class="loading">Recalculating totals…</div>`;

    try {
      await ensureSignedIn();
      const calculateOrderFees = httpsCallable(functions, "calculateOrderFees");
      const { data: fees } = await calculateOrderFees({
        items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title })),
        requiresDelivery,
        address: requiresDelivery ? deliveryAddress : null
      });

      if (summaryEl) {
        summaryEl.innerHTML = `
          <div class="summary-row"><span>Subtotal:</span> <span>${LSL(fees.subtotalMinorUnits || 0)}</span></div>
          ${requiresDelivery ? `<div class="summary-row"><span>Delivery Fee:</span> <span>${LSL(fees.deliveryFeeMinorUnits || 0)}</span></div>` : ""}
          <div class="summary-row"><span>Platform Fee:</span> <span>${LSL(fees.platformFeeMinorUnits || 0)}</span></div>
          <div class="summary-row total"><span>Total:</span> <span>${LSL(fees.totalMinorUnits || 0)}</span></div>`;
      }
    } catch (err) {
      if (summaryEl) summaryEl.innerHTML = `<div class="error">Fee calculation failed: ${escapeHtml(err.message)}</div>`;
    }
  }

  root.innerHTML = `
    <h1>Checkout</h1>
    <div class="checkout-form">
      <div class="form-group">
        <label>Fulfillment Option</label>
        <div style="display:flex; gap:16px;">
          <label style="font-weight:normal;"><input type="radio" name="deliveryOption" value="pickup" checked> Pickup / Self-Fulfillment</label>
          <label style="font-weight:normal;"><input type="radio" name="deliveryOption" value="delivery"> Standard Delivery</label>
        </div>
      </div>
      <div id="addressGroup" style="display:none;">
        <div class="form-group">
          <label for="streetInput">Delivery Street Address</label>
          <input type="text" id="streetInput" placeholder="e.g. Kingsway Road, House 42">
        </div>
        <div class="form-group">
          <label for="cityInput">City / Area</label>
          <input type="text" id="cityInput" value="Maseru">
        </div>
      </div>
    </div>

    <div class="summary" id="checkoutSummary">
      <div class="loading">Calculating totals…</div>
    </div>

    <div class="checkout-form">
      <div class="form-group">
        <label>Payment Method</label>
        <div class="payment-methods">
          <label class="payment-option"><input type="radio" name="paymentMethod" value="MOPAY" checked> MoPay Mobile Money / Card Payment</label>
          <label class="payment-option disabled"><input type="radio" name="paymentMethod" value="SWIFT_WALLET" disabled> Swift Wallet (Requires Android App with Biometric Auth)</label>
        </div>
      </div>
      <button class="btn primary" id="placeOrderBtn" style="width:100%;">Initiate Payment & Place Order</button>
      <div id="checkoutMsg"></div>
    </div>`;

  await updateCheckoutSummary();

  root.querySelectorAll("input[name='deliveryOption']").forEach(radio => {
    radio.addEventListener("change", (e) => {
      requiresDelivery = e.target.value === "delivery";
      const addrGroup = root.querySelector("#addressGroup");
      if (addrGroup) addrGroup.style.display = requiresDelivery ? "block" : "none";
      updateCheckoutSummary();
    });
  });

  root.querySelector("#streetInput")?.addEventListener("input", (e) => {
    deliveryAddress.street = e.target.value;
    updateCheckoutSummary();
  });

  root.querySelector("#cityInput")?.addEventListener("input", (e) => {
    deliveryAddress.city = e.target.value;
    updateCheckoutSummary();
  });

  root.querySelector("#placeOrderBtn")?.addEventListener("click", async () => {
    const msg = root.querySelector("#checkoutMsg");
    const btn = root.querySelector("#placeOrderBtn");
    if (requiresDelivery && !deliveryAddress.street.trim()) {
      if (msg) msg.innerHTML = `<span style="color:#ef4444;">Please provide a delivery street address.</span>`;
      return;
    }
    if (msg) msg.textContent = "Initiating payment session securely…";
    if (btn) btn.disabled = true;

    try {
      const createOrder = httpsCallable(functions, "createOrder");
      const idempotencyKey = `web_${Date.now()}_${Math.random().toString(36).slice(2)}`;

      const { data: result } = await createOrder({
        items: items.map(i => ({ listingId: i.listingId, quantity: i.quantity, title: i.title })),
        requiresDelivery,
        address: requiresDelivery ? deliveryAddress : null,
        paymentMethod: "MOPAY",
        idempotencyKey
      });

      if (result.error) {
        if (msg) msg.innerHTML = `<span style="color:#ef4444;">Order failed: ${escapeHtml(result.error)}</span>`;
        if (btn) btn.disabled = false;
        return;
      }

      localStorage.removeItem(CART_KEY);
      updateCartBadge();

      if (result.paymentUrl) {
        root.innerHTML = `
          <div class="success">
            <h1>Payment Session Initiated 💳</h1>
            <p>Order Reference: <strong>${escapeHtml(result.orderId)}</strong></p>
            <p>Redirecting to MoPay secure payment gateway…</p>
            <a class="btn primary" href="${escapeHtml(result.paymentUrl)}" style="max-width:260px; margin-top:16px;">Pay with MoPay →</a>
          </div>`;
        window.location.href = result.paymentUrl;
      } else {
        root.innerHTML = `
          <div class="success">
            <h1>Order Placed Successfully 🎉</h1>
            <p>Order Reference: <strong>${escapeHtml(result.orderId)}</strong></p>
            <p>Your order has been recorded securely on Swift Marketplace.</p>
            <a class="btn primary" href="/" style="max-width:220px; margin-top:16px;">Continue Browsing</a>
          </div>`;
      }
    } catch (err) {
      if (msg) msg.innerHTML = `<span style="color:#ef4444;">Order placement failed: ${escapeHtml(err.message)}</span>`;
      if (btn) btn.disabled = false;
    }
  });
}

// ---------- Router & Search Binding ----------

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

  root.innerHTML = `<div class="error">Page not found. <a href="/">Return Home</a></div>`;
}

document.addEventListener("click", (e) => {
  const a = e.target.closest("a[href^='/']");
  if (!a) return;
  e.preventDefault();
  navigate(a.getAttribute("href"));
});

window.addEventListener("popstate", route);

document.addEventListener("DOMContentLoaded", () => {
  updateCartBadge();
  route();

  const searchInput = document.getElementById("searchInput");
  if (searchInput) {
    searchInput.addEventListener("input", (e) => {
      searchQuery = e.target.value;
      if (location.pathname !== "/") navigate("/");
      else route();
    });
  }
});

export { addToCart, cartCount };
