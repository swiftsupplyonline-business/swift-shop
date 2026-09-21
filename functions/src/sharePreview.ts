import { onRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

// --- Config ----------------------------------------------------------------

const SHOPPING_APP_PACKAGE = "com.swiftshop.client";
const PLAY_STORE_URL = `https://play.google.com/store/apps/details?id=${SHOPPING_APP_PACKAGE}`;
const MAIN_APP_PACKAGE = "com.swiftsupply.app";
const MAIN_APP_PLAY_STORE_URL = `https://play.google.com/store/apps/details?id=${MAIN_APP_PACKAGE}`;
const SITE_ORIGIN = "https://swift-dev-3d3ae.web.app";
const SITE_NAME = "Swift Shop";

// --- Utilities -------------------------------------------------------------

function esc(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function formatPrice(minorUnits: number, currency = "LSL"): string {
  return `${currency} ${(minorUnits / 100).toFixed(2)}`;
}

// --- Page renderer ---------------------------------------------------------

interface PageOpts {
  title: string;
  description: string;
  imageUrl?: string;
  pageUrl: string;
  deepLink?: string;
  ogType?: string;
  extraMeta?: string;
  bodyContent: string;
}

function renderPage(opts: PageOpts): string {
  const {
    title, description, imageUrl = "", pageUrl,
    deepLink, ogType = "website", extraMeta = "", bodyContent
  } = opts;

  const safeTitle = esc(title);
  const safeDesc = esc(description);
  const safeImage = esc(imageUrl);
  const safeUrl = esc(pageUrl);
  const fullTitle = `${safeTitle} – ${SITE_NAME}`;

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <title>${fullTitle}</title>
  <meta name="description" content="${safeDesc}">
  <link rel="canonical" href="${safeUrl}">

  <!-- Open Graph -->
  <meta property="og:site_name" content="${esc(SITE_NAME)}">
  <meta property="og:type" content="${ogType}">
  <meta property="og:title" content="${safeTitle}">
  <meta property="og:description" content="${safeDesc}">
  <meta property="og:url" content="${safeUrl}">
  ${safeImage ? `<meta property="og:image" content="${safeImage}">` : ""}

  <!-- Twitter -->
  <meta name="twitter:card" content="${safeImage ? "summary_large_image" : "summary"}">
  <meta name="twitter:title" content="${safeTitle}">
  <meta name="twitter:description" content="${safeDesc}">
  ${safeImage ? `<meta name="twitter:image" content="${safeImage}">` : ""}

  ${extraMeta}

  ${deepLink ? `<link rel="alternate" href="${esc(deepLink)}">` : ""}

  <style>
    *, *::before, *::after { box-sizing: border-box; }
    :root {
      --blue: #1b6ef3;
      --blue-dark: #1558c7;
      --surface: #f5f7fa;
      --card: #ffffff;
      --text: #0f172a;
      --muted: #64748b;
      --border: #e2e8f0;
      --radius: 14px;
    }
    html, body {
      margin: 0; padding: 0;
      background: var(--surface);
      color: var(--text);
      font-family: -apple-system, "Segoe UI", Roboto, sans-serif;
      min-height: 100vh;
    }
    .wrap {
      max-width: 480px;
      margin: 0 auto;
      padding: 24px 16px 48px;
    }
    .brand {
      display: flex; align-items: center; gap: 10px;
      margin-bottom: 24px;
    }
    .brand-dot {
      width: 32px; height: 32px; border-radius: 8px;
      background: var(--blue);
      display: flex; align-items: center; justify-content: center;
    }
    .brand-dot svg { width: 18px; height: 18px; fill: white; }
    .brand-name { font-weight: 700; font-size: 17px; color: var(--text); }
    .card {
      background: var(--card);
      border-radius: var(--radius);
      overflow: hidden;
      box-shadow: 0 1px 4px rgba(0,0,0,.08);
      margin-bottom: 16px;
    }
    .hero-img {
      width: 100%; aspect-ratio: 1/1;
      object-fit: cover; display: block;
      background: #e9edf2;
    }
    .card-body { padding: 20px; }
    .label {
      font-size: 11px; font-weight: 600; letter-spacing: .06em;
      text-transform: uppercase; color: var(--muted);
      margin-bottom: 6px;
    }
    h1 { font-size: 20px; font-weight: 700; margin: 0 0 6px; line-height: 1.3; }
    .price {
      font-size: 22px; font-weight: 700; color: var(--blue);
      margin: 0 0 12px;
    }
    .desc {
      font-size: 14px; color: var(--muted); line-height: 1.6;
      margin: 0 0 16px;
      display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical;
      overflow: hidden;
    }
    .meta-row {
      display: flex; align-items: center; gap: 8px;
      font-size: 13px; color: var(--muted);
      margin-bottom: 8px;
    }
    .avatar {
      width: 32px; height: 32px; border-radius: 50%;
      object-fit: cover; background: #e9edf2; flex-shrink: 0;
    }
    .avatar-placeholder {
      width: 32px; height: 32px; border-radius: 50%;
      background: var(--blue); color: white;
      font-size: 13px; font-weight: 700;
      display: flex; align-items: center; justify-content: center;
      flex-shrink: 0;
    }
    .divider { border: none; border-top: 1px solid var(--border); margin: 16px 0; }
    .btn {
      display: block; width: 100%; padding: 15px;
      border-radius: 12px; text-decoration: none;
      font-size: 15px; font-weight: 600; text-align: center;
      margin-bottom: 10px; transition: opacity .15s;
    }
    .btn:active { opacity: .8; }
    .btn-primary { background: var(--blue); color: white; }
    .btn-secondary { background: var(--border); color: var(--text); }
    .unavailable {
      text-align: center; padding: 48px 16px;
    }
    .unavailable .icon { font-size: 48px; margin-bottom: 16px; }
    .unavailable h1 { font-size: 20px; margin-bottom: 8px; }
    .unavailable p { color: var(--muted); margin-bottom: 24px; }
    .sell-cta {
      background: var(--card); border-radius: var(--radius);
      padding: 16px 20px; text-align: center;
      border: 1px solid var(--border);
      margin-top: 8px;
    }
    .sell-cta p { font-size: 13px; color: var(--muted); margin: 0 0 10px; }
    .sell-cta a {
      font-size: 13px; font-weight: 600; color: var(--blue);
      text-decoration: none;
    }
    .grid {
      display: grid; grid-template-columns: 1fr 1fr;
      gap: 10px; margin-bottom: 16px;
    }
    .grid-item {
      background: var(--card); border-radius: 10px; overflow: hidden;
      box-shadow: 0 1px 3px rgba(0,0,0,.06);
    }
    .grid-item img {
      width: 100%; aspect-ratio: 1/1; object-fit: cover;
      display: block; background: #e9edf2;
    }
    .grid-item-body { padding: 8px 10px 10px; }
    .grid-item-title { font-size: 13px; font-weight: 600; margin: 0 0 2px;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .grid-item-price { font-size: 12px; color: var(--blue); font-weight: 700; }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="brand">
      <div class="brand-dot">
        <svg viewBox="0 0 24 24"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>
      </div>
      <span class="brand-name">Swift Shop</span>
    </div>
    ${bodyContent}
  </div>
  ${deepLink ? `
  <script>
    (function(){
      var dl = ${JSON.stringify(deepLink)};
      var ps = ${JSON.stringify(PLAY_STORE_URL)};
      var btn = document.getElementById('open-btn');
      if(!btn) return;
      btn.addEventListener('click', function(e){
        e.preventDefault();
        var t = setTimeout(function(){ window.location.href = ps; }, 1800);
        window.location.href = dl;
        window.addEventListener('blur', function(){ clearTimeout(t); }, {once:true});
      });
    })();
  </script>` : ""}
</body>
</html>`;
}

// --- Shared sub-renderers --------------------------------------------------

function unavailableBody(message: string): string {
  return `<div class="unavailable">
    <div class="icon">??</div>
    <h1>Not available</h1>
    <p>${esc(message)}</p>
    <a class="btn btn-primary" href="${esc(PLAY_STORE_URL)}">Browse Swift Shopping</a>
  </div>`;
}

function sellerCta(): string {
  return `<div class="sell-cta">
    <p>Want to sell on Swift?</p>
    <a href="${esc(MAIN_APP_PLAY_STORE_URL)}">Download Swift Store ?</a>
  </div>`;
}

function ctaButtons(deepLink: string, primaryLabel: string): string {
  return `
    <a id="open-btn" class="btn btn-primary" href="${esc(deepLink)}">${esc(primaryLabel)}</a>
    <a class="btn btn-secondary" href="${esc(PLAY_STORE_URL)}">Get the Swift Shopping app</a>`;
}

// --- Listing ---------------------------------------------------------------

export const renderListingPreview = onRequest(async (req, res) => {
  const match = req.path.match(/\/listing\/([^/]+)/);
  const listingId = match ? match[1] : null;
  if (!listingId) { res.status(400).send("Bad request"); return; }

  const db = admin.firestore();
  const pageUrl = `${SITE_ORIGIN}/listing/${listingId}`;

  try {
    const doc = await db.collection("listings").doc(listingId).get();
    if (!doc.exists) {
      res.status(404).send(renderPage({
        title: "Listing not found",
        description: "This item may have been removed.",
        pageUrl,
        bodyContent: unavailableBody("This listing may have been removed or is no longer available.")
      }));
      return;
    }

    const d = doc.data()!;
    const title: string = d.title || "Swift Listing";
    const description: string = d.description || "";
    const imageUrl: string = (d.imageUrls && d.imageUrls[0]) || "";
    const price = formatPrice(d.priceMinorUnits || 0, d.priceCurrency || "LSL");
    const isAvailable: boolean = d.isAvailable !== false;
    const shopId: string = d.shopId || "";
    const deepLink = `swiftshop://listing/${listingId}`;

    // Fetch shop name for attribution
    let shopName = "";
    if (shopId) {
      const shopDoc = await db.collection("shops").doc(shopId).get();
      if (shopDoc.exists) shopName = shopDoc.data()!.name || "";
    }

    const priceDisplay = price;
    const pageTitle = `${title} – ${priceDisplay}`;
    const pageDesc = description || `${shopName ? shopName + " · " : ""}${priceDisplay} on Swift Shopping`;

    const initials = shopName ? shopName.charAt(0).toUpperCase() : "S";

    const body = `
      <div class="card">
        ${imageUrl ? `<img class="hero-img" src="${esc(imageUrl)}" alt="${esc(title)}">` : ""}
        <div class="card-body">
          ${shopName ? `<div class="label">Sold by ${esc(shopName)}</div>` : ""}
          <h1>${esc(title)}</h1>
          <div class="price">${esc(priceDisplay)}</div>
          ${description ? `<p class="desc">${esc(description)}</p>` : ""}
          ${shopName ? `<div class="meta-row">
            <div class="avatar-placeholder">${esc(initials)}</div>
            <span>${esc(shopName)}</span>
          </div>` : ""}
          ${!isAvailable ? `<p style="color:#ef4444;font-size:13px;font-weight:600;">Currently unavailable</p>` : ""}
          <hr class="divider">
          ${isAvailable ? ctaButtons(deepLink, "Buy on Swift") : `<a class="btn btn-secondary" href="${esc(PLAY_STORE_URL)}">Get the Swift Shopping app</a>`}
        </div>
      </div>
      ${sellerCta()}`;

    res.set("Cache-Control", "public, max-age=60, s-maxage=60");
    res.status(200).send(renderPage({
      title: pageTitle,
      description: pageDesc,
      imageUrl,
      pageUrl,
      deepLink,
      ogType: "product",
      bodyContent: body
    }));
  } catch (err) {
    res.status(500).send(renderPage({
      title: "Swift Shop",
      description: "Something went wrong.",
      pageUrl,
      bodyContent: unavailableBody("Something went wrong loading this listing.")
    }));
  }
});

// --- Shop ------------------------------------------------------------------

export const renderShopPreview = onRequest(async (req, res) => {
  const match = req.path.match(/\/shop\/([^/]+)/);
  const shopId = match ? match[1] : null;
  if (!shopId) { res.status(400).send("Bad request"); return; }

  const db = admin.firestore();
  const pageUrl = `${SITE_ORIGIN}/shop/${shopId}`;
  const deepLink = `swiftshop://shop/${shopId}`;

  try {
    const doc = await db.collection("shops").doc(shopId).get();
    if (!doc.exists) {
      res.status(404).send(renderPage({
        title: "Shop not found",
        description: "This shop may have been removed.",
        pageUrl,
        bodyContent: unavailableBody("This shop may have been removed or is no longer available.")
      }));
      return;
    }

    const d = doc.data()!;
    const name: string = d.name || "Swift Shop";
    const description: string = d.description || "";
    const imageUrl: string = d.logoUrl || d.imageUrl || "";
    const location: string = d.location || d.city || "";
    // ownerId not exposed in preview

    // Fetch up to 4 active listings for this shop
    const listingsSnap = await db.collection("listings")
      .where("shopId", "==", shopId)
      .where("isAvailable", "==", true)
      .limit(4)
      .get();

    const listings = listingsSnap.docs.map(l => l.data());

    const gridHtml = listings.length > 0 ? `
      <div class="grid">
        ${listings.map(l => `
          <div class="grid-item">
            ${(l.imageUrls && l.imageUrls[0]) ? `<img src="${esc(l.imageUrls[0])}" alt="${esc(l.title || "")}">` : ""}
            <div class="grid-item-body">
              <div class="grid-item-title">${esc(l.title || "")}</div>
              <div class="grid-item-price">${esc(formatPrice(l.priceMinorUnits || 0, l.priceCurrency || "LSL"))}</div>
            </div>
          </div>`).join("")}
      </div>` : "";

    const pageDesc = description || `Shop ${name} on Swift Shopping${location ? " · " + location : ""}`;

    const body = `
      <div class="card">
        ${imageUrl ? `<img class="hero-img" src="${esc(imageUrl)}" alt="${esc(name)}" style="aspect-ratio:16/9;object-fit:cover;">` : ""}
        <div class="card-body">
          <div class="label">Swift Shop</div>
          <h1>${esc(name)}</h1>
          ${location ? `<div class="meta-row">?? <span>${esc(location)}</span></div>` : ""}
          ${description ? `<p class="desc">${esc(description)}</p>` : ""}
          <hr class="divider">
          ${ctaButtons(deepLink, "Open Shop in Swift")}
        </div>
      </div>
      ${gridHtml}
      ${sellerCta()}`;

    res.set("Cache-Control", "public, max-age=60, s-maxage=60");
    res.status(200).send(renderPage({
      title: name,
      description: pageDesc,
      imageUrl,
      pageUrl,
      deepLink,
      ogType: "website",
      bodyContent: body
    }));
  } catch (err) {
    res.status(500).send(renderPage({
      title: "Swift Shop",
      description: "Something went wrong.",
      pageUrl,
      bodyContent: unavailableBody("Something went wrong loading this shop.")
    }));
  }
});

// --- Seller / Profile ------------------------------------------------------

export const renderSellerPreview = onRequest(async (req, res) => {
  const match = req.path.match(/\/seller\/([^/]+)/);
  const sellerId = match ? match[1] : null;
  if (!sellerId) { res.status(400).send("Bad request"); return; }

  const db = admin.firestore();
  const pageUrl = `${SITE_ORIGIN}/seller/${sellerId}`;
  const deepLink = `swiftshop://seller/${sellerId}`;

  try {
    const profileDoc = await db.collection("profiles").doc(sellerId).get();
    if (!profileDoc.exists) {
      res.status(404).send(renderPage({
        title: "Seller not found",
        description: "This seller profile may have been removed.",
        pageUrl,
        bodyContent: unavailableBody("This seller profile could not be found.")
      }));
      return;
    }

    const p = profileDoc.data()!;
    const displayName: string = p.displayName || p.name || "Swift Seller";
    const avatarUrl: string = p.avatarUrl || p.photoUrl || "";
    const bio: string = p.bio || "";

    // Public shop for this seller
    const shopsSnap = await db.collection("shops")
      .where("ownerId", "==", sellerId)
      .limit(1)
      .get();

    let shopName = "";
    let shopId = "";
    if (!shopsSnap.empty) {
      const s = shopsSnap.docs[0].data();
      shopName = s.name || "";
      shopId = shopsSnap.docs[0].id;
    }

    // Representative listings
    const listingsSnap = await db.collection("listings")
      .where("shopId", "==", shopId)
      .where("isAvailable", "==", true)
      .limit(4)
      .get();

    const listings = shopId ? listingsSnap.docs.map(l => l.data()) : [];
    const gridHtml = listings.length > 0 ? `
      <div class="grid">
        ${listings.map(l => `
          <div class="grid-item">
            ${(l.imageUrls && l.imageUrls[0]) ? `<img src="${esc(l.imageUrls[0])}" alt="${esc(l.title || "")}">` : ""}
            <div class="grid-item-body">
              <div class="grid-item-title">${esc(l.title || "")}</div>
              <div class="grid-item-price">${esc(formatPrice(l.priceMinorUnits || 0, l.priceCurrency || "LSL"))}</div>
            </div>
          </div>`).join("")}
      </div>` : "";

    const initials = displayName.charAt(0).toUpperCase();
    const pageDesc = bio || `${displayName} sells on Swift Shopping${shopName ? " · " + shopName : ""}`;

    const body = `
      <div class="card">
        <div class="card-body">
          <div style="display:flex;align-items:center;gap:14px;margin-bottom:16px;">
            ${avatarUrl
              ? `<img class="avatar" src="${esc(avatarUrl)}" alt="${esc(displayName)}" style="width:56px;height:56px;">`
              : `<div class="avatar-placeholder" style="width:56px;height:56px;font-size:20px;">${esc(initials)}</div>`}
            <div>
              <h1 style="font-size:18px;margin:0 0 4px;">${esc(displayName)}</h1>
              ${shopName ? `<div class="label" style="margin:0;">${esc(shopName)}</div>` : ""}
            </div>
          </div>
          ${bio ? `<p class="desc">${esc(bio)}</p>` : ""}
          <hr class="divider">
          ${ctaButtons(deepLink, "View on Swift")}
        </div>
      </div>
      ${gridHtml}
      ${sellerCta()}`;

    res.set("Cache-Control", "public, max-age=60, s-maxage=60");
    res.status(200).send(renderPage({
      title: displayName,
      description: pageDesc,
      imageUrl: avatarUrl,
      pageUrl,
      deepLink,
      ogType: "profile",
      bodyContent: body
    }));
  } catch (err) {
    res.status(500).send(renderPage({
      title: "Swift Shop",
      description: "Something went wrong.",
      pageUrl,
      bodyContent: unavailableBody("Something went wrong loading this seller.")
    }));
  }
});

// --- Post ------------------------------------------------------------------

export const renderPostPreview = onRequest(async (req, res) => {
  const match = req.path.match(/\/post\/([^/]+)/);
  const postId = match ? match[1] : null;
  if (!postId) { res.status(400).send("Bad request"); return; }

  const db = admin.firestore();
  const pageUrl = `${SITE_ORIGIN}/post/${postId}`;
  const deepLink = `swiftshop://post/${postId}`;

  try {
    const doc = await db.collection("posts").doc(postId).get();
    if (!doc.exists) {
      res.status(404).send(renderPage({
        title: "Post not found",
        description: "This post may have been removed.",
        pageUrl,
        bodyContent: unavailableBody("This post may have been removed.")
      }));
      return;
    }

    const d = doc.data()!;
    const caption: string = d.caption || "";
    const authorName: string = d.authorName || "Swift Seller";
    const authorAvatar: string = d.authorAvatarUrl || "";
    const isVideo: boolean = d.type === "VIDEO";
    const imageUrl: string = isVideo
      ? (d.thumbnailUrl || "")
      : ((d.mediaUrls && d.mediaUrls[0]) || "");

    const initials = authorName.charAt(0).toUpperCase();
    const pageTitle = caption ? caption.substring(0, 60) : `Post by ${authorName}`;
    const pageDesc = caption || `See this on Swift Shopping`;

    const body = `
      <div class="card">
        ${imageUrl ? `<img class="hero-img" src="${esc(imageUrl)}" alt="${esc(pageTitle)}"${isVideo ? ` style="position:relative;"` : ""}>` : ""}
        <div class="card-body">
          <div class="meta-row" style="margin-bottom:12px;">
            ${authorAvatar
              ? `<img class="avatar" src="${esc(authorAvatar)}" alt="${esc(authorName)}">`
              : `<div class="avatar-placeholder">${esc(initials)}</div>`}
            <span style="font-weight:600;">${esc(authorName)}</span>
            ${isVideo ? `<span style="margin-left:auto;font-size:12px;color:var(--muted);">? Video</span>` : ""}
          </div>
          ${caption ? `<p class="desc" style="-webkit-line-clamp:6;">${esc(caption)}</p>` : ""}
          <hr class="divider">
          ${ctaButtons(deepLink, "View on Swift")}
        </div>
      </div>
      ${sellerCta()}`;

    res.set("Cache-Control", "public, max-age=60, s-maxage=60");
    res.status(200).send(renderPage({
      title: pageTitle,
      description: pageDesc,
      imageUrl,
      pageUrl,
      deepLink,
      ogType: isVideo ? "video.other" : "article",
      bodyContent: body
    }));
  } catch (err) {
    res.status(500).send(renderPage({
      title: "Swift Shop",
      description: "Something went wrong.",
      pageUrl,
      bodyContent: unavailableBody("Something went wrong loading this post.")
    }));
  }
});
