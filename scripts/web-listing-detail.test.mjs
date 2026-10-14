// Dependency-free tests for hosting/listing-detail.js (pure presentation module).
// Run: node scripts/web-listing-detail.test.mjs
import assert from "node:assert/strict";
import { renderListingDetailHTML, ctaFor, stockInfo, shopCoords, CTA } from "../hosting/listing-detail.js";

let n = 0;
const t = (name, fn) => { fn(); n++; console.log("✅", name); };

const shop = { id: "s1", name: "Mama Thato", logoUrl: "https://img.test/logo.png", isVerified: true,
  locationLat: -29.31, locationLng: 27.48, locationAddress: "Kingsway, Maseru" };
const base = { id: "l1", shopId: "s1", title: "Red Door", description: "Eau de parfum\nFresh",
  priceMinorUnits: 25000, imageUrls: ["https://img.test/a.jpg", "https://img.test/b.jpg", "https://img.test/c.jpg"],
  listingType: "BUY", stockQuantity: 5, isAvailable: true, likeCount: 12, commentCount: 3, bookmarkCount: 7,
  commitmentCount: 4, deliveryEstimateDays: 2, tags: ["perfume", "#gift"], customFields: [] };
const html = (over = {}, ctx = {}) => renderListingDetailHTML({ listing: { ...base, ...over }, shop, ...ctx });

t("renders title, price, stock, counts", () => {
  const h = html();
  for (const s of ["Red Door", "M250.00", "5 in stock", "♥ 12", "💬 3", "🔖 7", "4 verified buyers", "Estimated delivery: 2 days"])
    assert.ok(h.includes(s), s);
});
t("gallery: all images, dots and thumbs for multiple images", () => {
  const h = html();
  assert.equal((h.match(/class="ld-slide"/g) || []).length, 3);
  assert.equal((h.match(/class="ld-dot( is-on)?"/g) || []).length, 3);
  assert.equal((h.match(/data-ld-thumb=/g) || []).length, 3);
});
t("gallery: single image has no dots/thumbs; none shows placeholder", () => {
  assert.ok(!html({ imageUrls: ["https://img.test/a.jpg"] }).includes("ld-dots"));
  assert.ok(html({ imageUrls: [] }).includes("ld-noimg"));
});
t("rejects non-http(s) image URLs", () => {
  const h = html({ imageUrls: ["javascript:alert(1)", "data:text/html,x"] });
  assert.ok(!h.includes("javascript:") && h.includes("ld-noimg"));
});
t("escapes HTML in every user-controlled field (XSS)", () => {
  const evil = '<img src=x onerror=alert(1)>"';
  const h = renderListingDetailHTML({ listing: { ...base, title: evil, description: evil, tags: [evil],
    customFields: [{ label: evil, type: evil, options: [evil], isRequired: true }] },
    shop: { ...shop, name: evil, locationAddress: evil } });
  assert.ok(!h.includes("<img src=x"), "raw tag leaked");
  assert.ok(!/onerror=alert/.test(h.replace(/&lt;img src=x onerror=alert\(1\)&gt;/g, "")), "attr leaked");
});
t("out of stock disables quantity, add-to-cart and CTA", () => {
  const h = html({ stockQuantity: 0 });
  assert.ok(h.includes("Out of stock"));
  assert.match(h, /data-ld-cta[^>]*disabled/);
  assert.match(h, /data-ld-addcart[^>]*disabled/);
});
t("unavailable (isAvailable=false) is treated as out of stock", () => {
  assert.equal(stockInfo({ stockQuantity: 9, isAvailable: false }).available, false);
});
t("CTA label per listing type matches Android", () => {
  const expected = { BUY: "Buy Now", PLACE_ORDER: "Place Order", MAKE_PAYMENT: "Make Payment",
    SET_APPOINTMENT: "Book Appointment", REGISTER: "Register", DELIVER: "Request Delivery", TAKE_ME_THERE: "Take Me There" };
  for (const [type, label] of Object.entries(expected)) assert.equal(ctaFor(type).label, label);
  assert.equal(Object.keys(CTA).length, 7);
  assert.equal(ctaFor("SOMETHING_NEW").label, "Buy Now");
});
t("non-purchase types hide quantity/add-to-cart and stock chip", () => {
  const h = html({ listingType: "REGISTER", stockQuantity: 0 });
  assert.ok(!h.includes("data-ld-qty") && !h.includes("data-ld-addcart") && !h.includes("ld-stock"));
  assert.doesNotMatch(h, /data-ld-cta[^>]*disabled/);
});
t("Take Me There needs shop coordinates", () => {
  const withShop = renderListingDetailHTML({ listing: { ...base, listingType: "TAKE_ME_THERE" }, shop });
  assert.doesNotMatch(withShop, /data-ld-cta[^>]*disabled/);
  const noShop = renderListingDetailHTML({ listing: { ...base, listingType: "TAKE_ME_THERE" }, shop: { ...shop, locationLat: 0, locationLng: 0 } });
  assert.match(noShop, /data-ld-cta[^>]*disabled/);
});
t("seller card: name, verified, address, directions, shop link", () => {
  const h = html();
  for (const s of ["Sold by", "Mama Thato", "Verified shop", "Kingsway, Maseru", "destination=-29.31,27.48", 'href="/shop/s1"', "Visit Shop"])
    assert.ok(h.includes(s), s);
  assert.ok(html({ listingType: "DELIVER" }).includes("Delivery service by"));
});
t("missing shop still renders a usable seller link", () => {
  const h = renderListingDetailHTML({ listing: base, shop: null });
  assert.ok(h.includes('href="/shop/s1"') && h.includes("Shop"));
});
t("coords helper ignores 0,0 and bad values", () => {
  assert.equal(shopCoords({ locationLat: 0, locationLng: 0 }), null);
  assert.equal(shopCoords({ location: { lat: "x", lng: 1 } }), null);
  assert.deepEqual(shopCoords({ location: { lat: 1, lng: 2 } }), { lat: 1, lng: 2 });
});
t("description, options and tags", () => {
  const h = html({ customFields: [{ label: "Size", type: "select", options: ["S", "M"], isRequired: true }] });
  assert.ok(h.includes("Eau de parfum") && h.includes("Size") && h.includes("S, M") && h.includes("#perfume") && h.includes("#gift") && !h.includes("##gift"));
  assert.ok(html({ description: "" }).includes("hasn't added a description"));
});
t("rails render only when there are items and a card renderer", () => {
  const card = (x) => `<a class="card">${x.title}</a>`;
  const h = html({}, { moreFromShop: [{ title: "Other" }], similar: [], cardHTML: card });
  assert.ok(h.includes("More from this shop") && h.includes("Other") && !h.includes("You might also like"));
  assert.ok(!html({}, { moreFromShop: [{ title: "x" }] }).includes("More from this shop"));
});
t("standalone mode shows brand topbar; spa mode shows back link", () => {
  assert.ok(html({}, { standalone: true }).includes("ld-topbar"));
  assert.ok(html().includes("ld-back"));
});
t("social actions are read-only (no fake like/bookmark buttons)", () => {
  const h = html();
  assert.ok(!/data-ld-like|data-ld-bookmark|data-ld-comment/.test(h) && h.includes("in the Swift app"));
});
console.log(`\n${n} tests passed`);
