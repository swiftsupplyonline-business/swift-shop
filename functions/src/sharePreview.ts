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
void PLAY_STORE_URL;
// Firebase Functions provides GCLOUD_PROJECT for the project where this
// function is deployed, so generated links stay inside that environment.
const SITE_ORIGIN = `https://${process.env.GCLOUD_PROJECT}.web.app`;

// The Hosting shell (hosting/index.html) — header, styles, <main id="app">,
// and the <script src="/app.js"> that boots the real app — is the single
// source of truth for what SwiftShop looks like. Smart links must open the
// *actual* web app already on the right route, not a separate hand-built
// mini-page, so a shared listing or shop looks and behaves identically to
// browsing there directly. This fetches that shell once per warm function
// instance and injects per-listing/per-shop <head> tags into it: crawlers
// (which don't run JS) still see correct title/OG/Twitter tags in the raw
// HTML, and real visitors get app.js's router picking up location.pathname
// on load and rendering the live detail view, same as any in-app navigation.
//
// Trade-off: a warm instance can serve a shell cached from before the most
// recent Hosting deploy until it next cold-starts. Acceptable for now given
// how rarely the shell's own markup changes; revisit with a short TTL if
// that becomes a problem.
let shellHtmlPromise: Promise<string> | null = null;
async function getShellHtml(): Promise<string> {
  if (!shellHtmlPromise) {
    shellHtmlPromise = fetch(`${SITE_ORIGIN}/index.html`)
      .then((r) => {
        if (!r.ok) throw new Error(`shell fetch returned HTTP ${r.status}`);
        return r.text();
      })
      .catch((err) => {
        shellHtmlPromise = null; // let the next request retry instead of caching a failure
        throw err;
      });
  }
  return shellHtmlPromise;
}

function injectHead(shell: string, opts: {
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

  let out = shell.replace(/<title>.*?<\/title>/s, `<title>${safeTitle}</title>`);
  out = out.replace(
    /<meta name="description" content=".*?">/s,
    `<meta name="description" content="${safeDescription}">`
  );

  const metaTags = `
  <meta property="og:title" content="${safeTitle}">
  <meta property="og:description" content="${safeDescription}">
  ${safeImage ? `<meta property="og:image" content="${safeImage}">` : ""}
  <meta property="og:url" content="${safeUrl}">
  <meta property="og:type" content="product">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="${safeTitle}">
  <meta name="twitter:description" content="${safeDescription}">
  ${safeImage ? `<meta name="twitter:image" content="${safeImage}">` : ""}
</head>`;
  out = out.replace("</head>", metaTags);

  // Slim "open in app" banner for anyone arriving on a phone that has the
  // native app — deep-links straight to this listing/shop, falls through to
  // the live web page below it for everyone else.
  if (deepLink) {
    const banner = `<a href="${escapeHtml(deepLink)}" style="display:block;background:#111;color:#fff;text-align:center;padding:10px 14px;font:600 13px Inter,ui-sans-serif,sans-serif;text-decoration:none">Open in the SwiftShop app ↗</a>`;
    out = out.replace("<body>", `<body>\n${banner}`);
  }

  return out;
}

function notFoundOrigin(): { title: string; description: string; imageUrl: string; pageUrl: string; deepLink: null } {
  return {
    title: "SwiftShop",
    description: "Buy and sell in Maseru",
    imageUrl: "",
    pageUrl: SITE_ORIGIN,
    deepLink: null
  };
}

export const renderListingPreview = functions.onRequest(async (req, res) => {
  const match = req.path.match(/\/listing\/([^/]+)/);
  const listingId = match ? match[1] : null;

  let shell: string;
  try {
    shell = await getShellHtml();
  } catch (err) {
    res.status(502).send("SwiftShop is temporarily unavailable. Please try the link again shortly.");
    return;
  }

  if (!listingId) {
    res.status(404).send(injectHead(shell, notFoundOrigin()));
    return;
  }

  try {
    const doc = await admin.firestore().collection("listings").doc(listingId).get();
    if (!doc.exists) {
      res.status(404).send(injectHead(shell, {
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

    res.status(200).send(injectHead(shell, {
      title: `${title} — ${priceDisplay}`,
      description,
      imageUrl,
      pageUrl: `${SITE_ORIGIN}/listing/${listingId}`,
      deepLink: `swiftshop://listing/${listingId}`
    }));
  } catch (err) {
    res.status(500).send(injectHead(shell, {
      title: "SwiftShop",
      description: "Something went wrong loading this listing.",
      imageUrl: "",
      pageUrl: SITE_ORIGIN,
      deepLink: null
    }));
  }
});

export const renderShopPreview = functions.onRequest(async (req, res) => {
  const match = req.path.match(/\/shop\/([^/]+)/);
  const shopId = match ? match[1] : null;

  let shell: string;
  try {
    shell = await getShellHtml();
  } catch (err) {
    res.status(502).send("SwiftShop is temporarily unavailable. Please try the link again shortly.");
    return;
  }

  if (!shopId) {
    res.status(404).send(injectHead(shell, notFoundOrigin()));
    return;
  }

  try {
    const doc = await admin.firestore().collection("shops").doc(shopId).get();
    if (!doc.exists) {
      res.status(404).send(injectHead(shell, {
        title: "Shop not found",
        description: "This shop may have been removed.",
        imageUrl: "",
        pageUrl: `${SITE_ORIGIN}/shop/${shopId}`,
        deepLink: null
      }));
      return;
    }

    const data = doc.data()!;
    const name: string = data.name || "SwiftShop Shop";
    const description: string = data.description || "Check out this shop on SwiftShop";
    const imageUrl: string = data.coverUrl || data.logoUrl || "";

    res.status(200).send(injectHead(shell, {
      title: name,
      description,
      imageUrl,
      pageUrl: `${SITE_ORIGIN}/shop/${shopId}`,
      deepLink: `swiftshop://shop/${shopId}`
    }));
  } catch (err) {
    res.status(500).send(injectHead(shell, {
      title: "SwiftShop",
      description: "Something went wrong loading this shop.",
      imageUrl: "",
      pageUrl: SITE_ORIGIN,
      deepLink: null
    }));
  }
});