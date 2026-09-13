import * as functions from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

function escapeHtml(input: string): string {
  return input
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

const PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.swiftshop.client";
const SITE_ORIGIN = "https://swift-dev-3d3ae.web.app";

function renderPage(opts: {
  title: string;
  description: string;
  imageUrl: string;
  pageUrl: string;
  deepLink: string | null;
}): string {
  const { title, description, imageUrl, pageUrl, deepLink } = opts;
  const safeTitle = escapeHtml(title);
  const safeDescription = escapeHtml(description);
  const safeImage = escapeHtml(imageUrl);
  const safeUrl = escapeHtml(pageUrl);

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${safeTitle} — SwiftShop</title>
  <meta property="og:title" content="${safeTitle}">
  <meta property="og:description" content="${safeDescription}">
  <meta property="og:image" content="${safeImage}">
  <meta property="og:url" content="${safeUrl}">
  <meta property="og:type" content="product">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="${safeTitle}">
  <meta name="twitter:description" content="${safeDescription}">
  <meta name="twitter:image" content="${safeImage}">
  <style>
    body { font-family: -apple-system, Roboto, sans-serif; max-width: 480px; margin: 40px auto; padding: 0 20px; text-align: center; color: #1a1a1a; }
    img { width: 100%; max-width: 320px; border-radius: 12px; margin-bottom: 20px; }
    h1 { font-size: 20px; margin: 0 0 8px; }
    p { color: #555; margin: 0 0 24px; }
    a.btn { display: block; padding: 14px; border-radius: 10px; text-decoration: none; font-weight: 600; margin-bottom: 12px; }
    a.primary { background: #1b6ef3; color: white; }
    a.secondary { background: #eee; color: #1a1a1a; }
  </style>
</head>
<body>
  ${safeImage ? `<img src="${safeImage}" alt="${safeTitle}">` : ""}
  <h1>${safeTitle}</h1>
  <p>${safeDescription}</p>
  ${deepLink ? `<a class="btn primary" href="${escapeHtml(deepLink)}">Open in SwiftShop</a>` : ""}
  <a class="btn secondary" href="${PLAY_STORE_URL}">Get the SwiftShop app</a>
</body>
</html>`;
}

export const renderListingPreview = functions.onRequest(async (req, res) => {
  const match = req.path.match(/\/listing\/([^/]+)/);
  const listingId = match ? match[1] : null;

  if (!listingId) {
    res.status(404).send(renderPage({
      title: "SwiftShop",
      description: "Buy and sell in Maseru",
      imageUrl: "",
      pageUrl: SITE_ORIGIN,
      deepLink: null
    }));
    return;
  }

  try {
    const doc = await admin.firestore().collection("listings").doc(listingId).get();
    if (!doc.exists) {
      res.status(404).send(renderPage({
        title: "Listing not found",
        description: "This listing may have been removed.",
        imageUrl: "",
        pageUrl: `${SITE_ORIGIN}/listing/${listingId}`,
        deepLink: null
      }));
      return;
    }

    const data = doc.data()!;
    const title: string = data.title || "SwiftShop Listing";
    const description: string = data.description || "Check this out on SwiftShop";
    const imageUrl: string = (data.imageUrls && data.imageUrls[0]) || "";
    const priceMinorUnits: number = data.priceMinorUnits || 0;
    const priceCurrency: string = data.priceCurrency || "LSL";
    const priceDisplay = `${priceCurrency} ${(priceMinorUnits / 100).toFixed(2)}`;

    res.status(200).send(renderPage({
      title: `${title} — ${priceDisplay}`,
      description,
      imageUrl,
      pageUrl: `${SITE_ORIGIN}/listing/${listingId}`,
      deepLink: `swiftshop://listing/${listingId}`
    }));
  } catch (err) {
    res.status(500).send(renderPage({
      title: "SwiftShop",
      description: "Something went wrong loading this listing.",
      imageUrl: "",
      pageUrl: SITE_ORIGIN,
      deepLink: null
    }));
  }
});
