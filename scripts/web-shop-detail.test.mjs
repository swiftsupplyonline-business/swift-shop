// Dependency-free tests for hosting/shop-detail.js and hosting/ui-kit.js (pure presentation modules).
// Run: node scripts/web-shop-detail.test.mjs
import assert from "node:assert/strict";
import { renderShopDetailHTML, filterListings, listingTypes, deriveRails, shopWhatsappUrl, deepLinkForShop, SORTS } from "../hosting/shop-detail.js";
import { railHTML, esc, safeUrl } from "../hosting/ui-kit.js";

let n = 0;
const t = (name, fn) => { fn(); n++; console.log("✅", name); };

const shop = { id: "s1", name: "Skin Care & Co", description: "Beauty in Maseru", logoUrl: "https://img.test/l.png", coverUrl: "https://img.test/c.png",
  category: "Beauty", isVerified: true, isActive: true, rating: 4.6, reviewCount: 38, followerCount: 1243, listingCount: 14,
  whatsappNumber: "+266 5000-0000", locationAddress: "Kingsway, Maseru", locationLat: -29.3, locationLng: 27.4 };
const L = (i, o = {}) => ({ id: "l" + i, shopId: "s1", title: "Item " + i, description: "", priceMinorUnits: 1000 * i, listingType: "BUY",
  createdAt: 1000 - i, likeCount: 0, commitmentCount: 0, bookmarkCount: 0, commentCount: 0, tags: [], ...o });
const listings = [1, 2, 3, 4, 5, 6, 7, 8].map((i) => L(i));
const card = (l) => `<a class="card">${l.title}</a>`;
const html = (over = {}, ctx = {}) => renderShopDetailHTML({ shop: { ...shop, ...over }, listings, otherShops: [], cardHTML: card, ...ctx });

t("header: name, verified, category, rating, followers, products, reviews", () => {
  const h = html();
  for (const s of ["Skin Care &amp; Co", "Verified shop", "Beauty", "4.6", "(38)", "1.2k", ">14<", ">38<", "Kingsway, Maseru"]) assert.ok(h.includes(s), s);
  assert.equal((h.match(/4\.6/g) || []).length, 2, "rating shown once in meta (+ title attr), not repeated in stats");
});
t("cover + logo render only for http(s) URLs; fallbacks otherwise", () => {
  assert.ok(html().includes("sd-cover-img") && html().includes("sd-logo"));
  const h = html({ coverUrl: "javascript:alert(1)", logoUrl: "data:text/html,x" });
  assert.ok(!h.includes("javascript:") && !h.includes("data:text") && h.includes("is-empty") && h.includes("sd-logo-fallback"));
  assert.ok(h.includes(">S<"), "initial letter fallback");
});
t("WhatsApp: digits only, wa.me link; hidden when number missing/too short", () => {
  assert.ok(shopWhatsappUrl(shop).startsWith("https://wa.me/26650000000?text="));
  assert.equal(shopWhatsappUrl({ whatsappNumber: "123" }), "");
  assert.ok(html().includes("wa.me/26650000000"));
  assert.ok(!html({ whatsappNumber: "" }).includes("wa.me"));
});
t("Directions only with real coordinates", () => {
  assert.ok(html().includes("destination=-29.3,27.4"));
  assert.ok(!html({ locationLat: 0, locationLng: 0 }).includes("google.com/maps"));
});
t("escapes every user-controlled field (XSS)", () => {
  const evil = '<img src=x onerror=alert(1)>"';
  const h = renderShopDetailHTML({ shop: { ...shop, name: evil, description: evil, category: evil, locationAddress: evil }, listings: [], otherShops: [{ id: "s2", name: evil, category: evil }], cardHTML: card });
  assert.ok(!h.includes("<img src=x"), "raw tag leaked");
  assert.ok(!/ onerror=alert/.test(h.replace(/&lt;img src=x onerror=alert\(1\)&gt;/g, "")), "attribute leaked");
  assert.equal(esc("<&>\"'"), "&lt;&amp;&gt;&quot;&#39;");
  assert.equal(safeUrl("ftp://x"), "");
});
t("inactive shop is flagged", () => {
  assert.ok(html({ isActive: false }).includes("Not taking orders"));
  assert.ok(!html().includes("Not taking orders"));
});
t("no products -> friendly empty state, no toolbar", () => {
  const h = renderShopDetailHTML({ shop, listings: [], otherShops: [], cardHTML: card });
  assert.ok(h.includes("No products yet") && !h.includes("data-sd-q") && !h.includes("data-sd-grid"));
});
t("type chips: only when >1 type, with counts and Android labels", () => {
  assert.ok(!html().includes("data-sd-type"), "single type -> no chips");
  const mixed = [...listings, L(9, { listingType: "SET_APPOINTMENT" }), L(10, { listingType: "SET_APPOINTMENT" }), L(11, { listingType: "PLACE_ORDER" })];
  const h = renderShopDetailHTML({ shop, listings: mixed, otherShops: [], cardHTML: card });
  for (const s of ["All (11)", "Buy Now (8)", "Book Appointment (2)", "Place Order (1)"]) assert.ok(h.includes(s), s);
  assert.deepEqual(listingTypes(mixed).map((x) => x.type), ["BUY", "SET_APPOINTMENT", "PLACE_ORDER"]);
});
t("filter: search matches title, description, category and tags; case-insensitive", () => {
  const rows = [L(1, { title: "Red Lipstick" }), L(2, { description: "great for DRY skin" }), L(3, { category: "perfume" }), L(4, { tags: ["gift"] }), L(5)];
  assert.deepEqual(filterListings(rows, { q: "lipstick" }).map((x) => x.id), ["l1"]);
  assert.deepEqual(filterListings(rows, { q: "dry" }).map((x) => x.id), ["l2"]);
  assert.deepEqual(filterListings(rows, { q: "PERFUME" }).map((x) => x.id), ["l3"]);
  assert.deepEqual(filterListings(rows, { q: "gift" }).map((x) => x.id), ["l4"]);
  assert.equal(filterListings(rows, { q: "zzz" }).length, 0);
  assert.equal(filterListings(rows, { q: "  " }).length, 5);
});
t("filter: type and sorts", () => {
  const rows = [L(1, { listingType: "REGISTER", priceMinorUnits: 300, createdAt: 1 }), L(2, { priceMinorUnits: 100, createdAt: 3, likeCount: 9 }), L(3, { priceMinorUnits: 200, createdAt: 2 })];
  assert.deepEqual(filterListings(rows, { type: "REGISTER" }).map((x) => x.id), ["l1"]);
  assert.deepEqual(filterListings(rows, { sort: "newest" }).map((x) => x.id), ["l2", "l3", "l1"]);
  assert.deepEqual(filterListings(rows, { sort: "price-asc" }).map((x) => x.id), ["l2", "l3", "l1"]);
  assert.deepEqual(filterListings(rows, { sort: "price-desc" }).map((x) => x.id), ["l1", "l3", "l2"]);
  assert.deepEqual(filterListings(rows, { sort: "popular" }).map((x) => x.id)[0], "l2");
  assert.equal(Object.keys(SORTS).length, 4);
});
t("rails: popular needs >=4 listings and engagement; new arrivals never repeats popular", () => {
  assert.deepEqual(deriveRails([L(1), L(2), L(3)]), { popular: [], newest: [] });
  assert.equal(deriveRails(listings).popular.length, 0, "no engagement -> no popular rail");
  const eng = listings.map((l, i) => (i < 3 ? { ...l, likeCount: 10 - i } : l));
  const r = deriveRails(eng);
  assert.deepEqual(r.popular.map((x) => x.id), ["l1", "l2", "l3"]);
  assert.ok(r.newest.length >= 3 && r.newest.every((x) => !r.popular.some((p) => p.id === x.id)));
  assert.deepEqual(deriveRails(listings.slice(0, 5)).newest, [], "fewer than 6 listings -> no new-arrivals rail");
});
t("rails render in the page only when they have content", () => {
  const eng = listings.map((l, i) => (i < 3 ? { ...l, likeCount: 10 - i } : l));
  const h = renderShopDetailHTML({ shop, listings: eng, otherShops: [{ id: "s2", name: "Other", category: "Food" }], cardHTML: card });
  for (const s of ["Popular right now", "New arrivals", "More shops to explore", 'href="/shop/s2"']) assert.ok(h.includes(s), s);
  assert.ok(!html().includes("Popular right now") && !html().includes("More shops to explore"));
});
t("standalone vs in-app top bar; follow is app-only (no fake follow button logic)", () => {
  assert.ok(html({}, { standalone: true }).includes("sd-topbar"));
  assert.ok(html().includes('class="sd-back"'));
  assert.ok(html().includes("swiftshop://shop/s1") && deepLinkForShop("a b") === "swiftshop://shop/a%20b");
});
t("ui-kit rail: empty -> '', unique labelled id, escaped title, optional See all", () => {
  assert.equal(railHTML({ id: "x", title: "T", items: [], renderItem: card }), "");
  assert.equal(railHTML({ id: "x", title: "T", items: [1], renderItem: null }), "");
  const h = railHTML({ id: "My Rail!", title: "<b>T</b>", subtitle: "sub", items: [{ title: "a" }], renderItem: card, seeAllHref: "/market", seeAllLabel: "All" });
  assert.ok(h.includes('id="rl-my-rail"') && h.includes('aria-labelledby="rl-my-rail"') && h.includes("&lt;b&gt;T&lt;/b&gt;") && h.includes('href="/market"') && h.includes("data-rl-prev"));
  assert.ok(!railHTML({ id: "y", title: "T", items: [{ title: "a" }], renderItem: card }).includes("rl-all"));
});
console.log(`\n${n} tests passed`);
