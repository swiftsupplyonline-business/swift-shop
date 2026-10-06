import * as functions from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { parseSharePath, isScannableLegacySlug, isPubliclyVisible } from "./shareRouting";
import { normalizeShareSlug } from "./shareSlug";

function escapeHtml(input: string): string {
    return input
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

const PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.swiftshop.client";
const SITE_ORIGIN = `https://${process.env.GCLOUD_PROJECT}.web.app`;
const SHARE_DESCRIPTION = "Order now - Delivery in Maseru";

function absoluteUrl(value: string): string {
    if (!value) return "";
    if (/^https?:\/\//i.test(value)) return value;
    return new URL(value.startsWith("/") ? value : `/${value}`, SITE_ORIGIN).toString();
}

function shareUrl(shopSlug: string, productSlug: string): string {
    return `${SITE_ORIGIN}/s/${encodeURIComponent(shopSlug)}/${encodeURIComponent(productSlug)}`;
}

function deliveryShareUrl(shopSlug: string, productSlug: string): string {
    return `${SITE_ORIGIN}/d/${encodeURIComponent(shopSlug)}/${encodeURIComponent(productSlug)}`;
}

function whatsappShareUrl(product: string, shop: string, whatsappNumber?: string): string {
    const message = `Hi, I want ${product} from ${shop}`;
    const encodedMessage = encodeURIComponent(message);
    const digits = String(whatsappNumber || "").replace(/\D/g, "");
    return digits ? `https://wa.me/${digits}?text=${encodedMessage}` : `https://wa.me/?text=${encodedMessage}`;
}

function renderPage(opts: {
    title: string;
    description: string;
    imageUrl: string;
    pageUrl: string;
    deepLink: string | null;
    listingId: string | null;
    listingType: string | null;
    priceDisplay: string | null;
    priceAmount: string | null;
    priceCurrency: string | null;
    inStock: boolean;
    shopName: string | null;
    whatsappUrl: string | null;
}): string {
    const {
        title, description, imageUrl, pageUrl, deepLink, listingId, listingType,
        priceDisplay, priceAmount, priceCurrency, inStock, shopName, whatsappUrl
    } = opts;

    const safeTitle = escapeHtml(title);
    const safeDescription = escapeHtml(description);
    const safeImage = escapeHtml(absoluteUrl(imageUrl));
    const safeUrl = escapeHtml(pageUrl);
    const safeShopName = shopName ? escapeHtml(shopName) : "";

    const isDeliveryListing = listingType === "DELIVER";
    const commerceButtons = listingId ? `
    <div class="price">${priceDisplay ? escapeHtml(priceDisplay) : ""}</div>
    ${safeShopName ? `<p class="shop">${isDeliveryListing ? "Delivery service by" : "Sold by"} ${safeShopName}</p>` : ""}
    <div class="actions">
      ${isDeliveryListing
        ? `<a class="btn primary" href="${escapeHtml(deepLink || "#")}">Request delivery in SwiftShop</a>`
        : `<button class="btn primary" id="buyNowBtn" ${inStock ? "" : "disabled"}>Buy</button>`}
      <a class="btn whatsapp" href="${whatsappUrl ? escapeHtml(whatsappUrl) : "#"}" ${whatsappUrl ? "" : 'aria-disabled="true"'}>${isDeliveryListing ? "Contact provider on WhatsApp" : "Order via WhatsApp"}</a>
    </div>
    ${!inStock ? '<p class="oos">Currently unavailable</p>' : ""}
    ` : "";

    // Progressive enhancement: the markup below is a complete, crawler-friendly fallback.
    // The browser module replaces it with the full Android-parity listing view (hosting/listing-detail.js);
    // if anything fails, the original markup is restored and the basic Buy button keeps working.
    const jsonForScript = (value: unknown) => JSON.stringify(value).replace(/</g, "\\u003c");
    const commerceScript = listingId ? `
    <script type="module">
      const id = ${jsonForScript(listingId)};
      const title = ${jsonForScript(title)};
      const isDelivery = ${isDeliveryListing ? "true" : "false"};
      const root = document.getElementById("swift-listing-root");
      const fallbackHtml = root ? root.innerHTML : "";
      const useFallback = () => {
        if (root) root.innerHTML = fallbackHtml;
        document.body.classList.remove("ld-enhanced");
        if (isDelivery) return;
        document.getElementById("buyNowBtn")?.addEventListener("click", async () => {
          const { addToCart } = await import("/app.js");
          addToCart(id, title, 1);
          window.location.href = "/checkout";
        });
      };
      if (!root) { useFallback(); }
      else {
        try {
          const { mountListingDetail } = await import("/app.js");
          document.body.classList.add("ld-enhanced");
          const ok = await mountListingDetail(root, { listingId: id, standalone: true });
          if (!ok) useFallback();
        } catch (err) {
          console.error("Swift listing view failed to load; showing basic page", err);
          useFallback();
        }
      }
    </script>` : "";

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${safeTitle} — SwiftShop</title>
  <meta name="description" content="${safeDescription}">
  <link rel="canonical" href="${safeUrl}">
  <meta property="og:type" content="product">
  <meta property="og:title" content="${safeTitle}">
  <meta property="og:description" content="${safeDescription}">
  <meta property="og:image" content="${safeImage}">
  <meta property="og:url" content="${safeUrl}">
  ${priceAmount ? `<meta property="product:price:amount" content="${escapeHtml(priceAmount)}">` : ""}
  ${priceCurrency ? `<meta property="product:price:currency" content="${escapeHtml(priceCurrency)}">` : ""}
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="${safeTitle}">
  <meta name="twitter:description" content="${safeDescription}">
  <meta name="twitter:image" content="${safeImage}">
  <link rel="stylesheet" href="/listing-detail.css">
  <style>
    body { font-family: -apple-system, Roboto, sans-serif; max-width: 480px; margin: 24px auto; padding: 0 20px; color: #1a1a1a; }
    img { width: 100%; max-width: 420px; aspect-ratio: 1; object-fit: cover; border-radius: 12px; margin-bottom: 18px; background: #eee; }
    h1 { font-size: 22px; margin: 0 0 8px; }
    p { color: #555; margin: 0 0 18px; line-height: 1.45; }
    .price { font-size: 22px; font-weight: 700; color: #1b6ef3; margin-bottom: 4px; }
    .shop { font-size: 14px; }
    .actions { display: flex; flex-direction: column; gap: 10px; margin: 20px 0; }
    .btn { display: block; width: 100%; padding: 15px; border-radius: 10px; border: none; box-sizing: border-box; font-weight: 700; font-size: 16px; text-align: center; text-decoration: none; cursor: pointer; }
    .btn.primary { background: #1b6ef3; color: white; }
    .btn.whatsapp { background: #25d366; color: white; }
    .btn:disabled, .btn[aria-disabled="true"] { opacity: .5; pointer-events: none; }
    .oos { color: #c00; font-size: 13px; }
  </style>
</head>
<body data-share-page="true">
  <div id="swift-listing-root">
  ${safeImage ? `<img src="${safeImage}" alt="${safeTitle}" loading="eager">` : ""}
  <h1>${safeTitle}</h1>
  <p>${safeDescription}</p>
  ${commerceButtons}
  ${deepLink ? `<a class="btn" href="${escapeHtml(deepLink)}">Open in SwiftShop app</a>` : ""}
  <a class="btn" href="${PLAY_STORE_URL}">Get the SwiftShop app</a>
  </div>
  ${commerceScript}
</body>
</html>`;
}


async function findShopBySlug(db: FirebaseFirestore.Firestore, shopSlug: string): Promise<FirebaseFirestore.QueryDocumentSnapshot | null> {
    const direct = await db.collection("shops").where("shareSlug", "==", shopSlug).limit(2).get();
    if (direct.size === 1) return direct.docs[0];
    if (direct.size > 1) return null;

    // Transitional fallback for shops created before shareSlug was introduced.
    if (!isScannableLegacySlug(shopSlug)) return null;
    const legacy = await db.collection("shops").get();
    const matches = legacy.docs.filter(doc => normalizeShareSlug(doc.data().name) === shopSlug);
    return matches.length === 1 ? matches[0] : null;
}

async function findListingBySlug(
    db: FirebaseFirestore.Firestore,
    shopId: string,
    productSlug: string
): Promise<FirebaseFirestore.QueryDocumentSnapshot | null> {
    const direct = await db.collection("listings")
        .where("shopId", "==", shopId)
        .where("shareSlug", "==", productSlug)
        .limit(2)
        .get();
    if (direct.size === 1) return direct.docs[0];
    if (direct.size > 1) return null;

    // Transitional fallback for listings created before shareSlug was introduced.
    if (!isScannableLegacySlug(productSlug)) return null;
    const legacy = await db.collection("listings").where("shopId", "==", shopId).get();
    const matches = legacy.docs.filter(doc => normalizeShareSlug(doc.data().title) === productSlug);
    return matches.length === 1 ? matches[0] : null;
}

function listingResponse(
    res: any,
    listingDoc: FirebaseFirestore.QueryDocumentSnapshot,
    shopDoc: FirebaseFirestore.QueryDocumentSnapshot
): void {
    const listing = listingDoc.data();
    const shop = shopDoc.data();
    const title = String(listing.title || "SwiftShop Listing");
    const shopName = String(shop.name || "SwiftShop");
    const shopSlug = String(shop.shareSlug || normalizeShareSlug(shopName));
    const productSlug = String(listing.shareSlug || normalizeShareSlug(title));
    const imageUrl = absoluteUrl(
        Array.isArray(listing.imageUrls) && listing.imageUrls[0]
            ? String(listing.imageUrls[0])
            : Array.isArray(listing.images) && listing.images[0]
                ? String(listing.images[0])
                : ""
    );
    const priceMinorUnits = Number(listing.priceMinorUnits || 0);
    const priceCurrency = String(listing.priceCurrency || "LSL");
    const priceAmount = (priceMinorUnits / 100).toFixed(2);
    const priceDisplay = `M${priceAmount}`;
    const inStock = listing.isAvailable !== false && Number(listing.stockQuantity ?? 1) > 0;
    const listingType = String(listing.listingType || "BUY");
    const canonicalUrl = listingType === "DELIVER"
        ? deliveryShareUrl(shopSlug, productSlug)
        : shareUrl(shopSlug, productSlug);

    res.status(200).send(renderPage({
        title: `${title} — ${priceDisplay}`,
        description: listingType === "DELIVER" ? "Book a delivery service in Maseru" : SHARE_DESCRIPTION,
        imageUrl,
        pageUrl: canonicalUrl,
        deepLink: `swiftshop://listing/${listingDoc.id}`,
        listingId: listingDoc.id,
        listingType,
        priceDisplay,
        priceAmount,
        priceCurrency,
        inStock,
        shopName,
        whatsappUrl: whatsappShareUrl(title, shopName, String(shop.whatsappNumber || ""))
    }));
}

export const renderListingPreview = functions.onRequest({ invoker: "public" }, async (req, res) => {
    const listingId = req.path.match(/\/listing\/([^/]+)/)?.[1];
    if (!listingId) {
        res.status(404).send("Listing not found");
        return;
    }

    try {
        const db = admin.firestore();
        const listingDoc = await db.collection("listings").doc(listingId).get();
        if (!listingDoc.exists) {
            res.status(404).send("Listing not found");
            return;
        }

        const listing = listingDoc.data()!;
        if (!isPubliclyVisible(listing.status)) {
            res.status(404).send("Listing not found");
            return;
        }
        const shopDoc = await db.collection("shops").doc(String(listing.shopId || "")).get();
        if (!shopDoc.exists) {
            res.status(404).send("Shop not found");
            return;
        }

        const shop = shopDoc.data()!;
        const shopSlug = String(shop.shareSlug || normalizeShareSlug(shop.name));
        const productSlug = String(listing.shareSlug || normalizeShareSlug(listing.title));
        const listingType = String(listing.listingType || "BUY");
        res.redirect(301, listingType === "DELIVER"
            ? deliveryShareUrl(shopSlug, productSlug)
            : shareUrl(shopSlug, productSlug));
    } catch (err) {
        console.error("renderListingPreview failed", err);
        res.status(500).send("Unable to load listing");
    }
});

export const renderSharedListing = functions.onRequest({ invoker: "public" }, async (req, res) => {
    const parsed = parseSharePath("s", req.path);
    if (!parsed) {
        res.status(404).send("Listing not found");
        return;
    }
    const { shopSlug, productSlug } = parsed;

    try {
        const db = admin.firestore();
        const shopDoc = await findShopBySlug(db, shopSlug);
        if (!shopDoc) {
            res.status(404).send("Shop not found");
            return;
        }

        const listingDoc = await findListingBySlug(db, shopDoc.id, productSlug);
        if (!listingDoc || !isPubliclyVisible(listingDoc.data().status)) {
            res.status(404).send("Listing not found");
            return;
        }

        if (String(listingDoc.data().listingType || "BUY") === "DELIVER") {
            res.redirect(301, deliveryShareUrl(shopSlug, productSlug));
            return;
        }
        listingResponse(res, listingDoc, shopDoc);
    } catch (err) {
        console.error("renderSharedListing failed", err);
        res.status(500).send("Unable to load listing");
    }
});

export const renderSharedDelivery = functions.onRequest({ invoker: "public" }, async (req, res) => {
    const parsed = parseSharePath("d", req.path);
    if (!parsed) {
        res.status(404).send("Delivery listing not found");
        return;
    }
    const { shopSlug, productSlug } = parsed;

    try {
        const db = admin.firestore();
        const shopDoc = await findShopBySlug(db, shopSlug);
        if (!shopDoc) {
            res.status(404).send("Delivery provider shop not found");
            return;
        }

        const listingDoc = await findListingBySlug(db, shopDoc.id, productSlug);
        if (!listingDoc || !isPubliclyVisible(listingDoc.data().status) || String(listingDoc.data().listingType || "") !== "DELIVER") {
            res.status(404).send("Delivery listing not found");
            return;
        }

        listingResponse(res, listingDoc, shopDoc);
    } catch (err) {
        console.error("renderSharedDelivery failed", err);
        res.status(500).send("Unable to load delivery listing");
    }
});

export const renderShopPreview = functions.onRequest({ invoker: "public" }, async (req, res) => {
    const shopId = req.path.match(/\/shop\/([^/]+)/)?.[1];
    if (!shopId) {
        res.status(404).send("Shop not found");
        return;
    }

    try {
        const doc = await admin.firestore().collection("shops").doc(shopId).get();
        if (!doc.exists) {
            res.status(404).send("Shop not found");
            return;
        }

        const data = doc.data()!;
        const name = String(data.name || "SwiftShop Shop");
        const description = String(data.description || "Check out this shop on SwiftShop");
        const imageUrl = absoluteUrl(String(data.coverUrl || data.logoUrl || ""));
        res.status(200).send(renderPage({
            title: name,
            description,
            imageUrl,
            pageUrl: `${SITE_ORIGIN}/shop/${shopId}`,
            deepLink: `swiftshop://shop/${shopId}`,
            listingId: null,
            listingType: null,
            priceDisplay: null,
            priceAmount: null,
            priceCurrency: null,
            inStock: false,
            shopName: null,
            whatsappUrl: null
        }));
    } catch (err) {
        console.error("renderShopPreview failed", err);
        res.status(500).send("Unable to load shop");
    }
});
