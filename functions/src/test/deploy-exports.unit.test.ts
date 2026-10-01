import * as fs from "fs";
import * as path from "path";

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "swift-shop-reconciled";

// Every Hosting rewrite that targets a Cloud Function must be exported by functions/src/index.ts,
// otherwise `firebase deploy` produces a dead route.
describe("deployment exports", () => {
  const firebaseJson = JSON.parse(fs.readFileSync(path.join(__dirname, "../../../firebase.json"), "utf8"));
  const hosting = Array.isArray(firebaseJson.hosting) ? firebaseJson.hosting : [firebaseJson.hosting];
  const rewriteFunctions: string[] = hosting.flatMap((h: any) => (h.rewrites || []).map((r: any) => r.function).filter(Boolean));
  const exported = require("../index");

  test("firebase.json references at least the known function rewrites", () => {
    for (const name of ["publicMarketplace", "payPreview", "renderSharedListing", "renderSharedDelivery", "renderShopPreview", "renderListingPreview"]) {
      expect(rewriteFunctions).toContain(name);
    }
  });
  test.each(rewriteFunctions)("hosting rewrite target %s is exported", (name) => {
    expect(typeof exported[name]).toBe("function");
  });
  test.each([
    "updateFcmToken", "notifyOnMessage", "notifyOnOrderStatusChange", "notifyOnDeliveryRequestCreated",
    "notifyOnDeliveryRequestResponded", "notifyOnDeliveryStatusChange",
    "createPurchaseOrder", "verifyMopayPayment", "confirmDelivery", "cancelOrder", "updateOrderStatus",
    "createDeliveryRequest", "acceptDeliveryRequest", "declineDeliveryRequest", "cancelDeliveryRequest",
    "createDeliveryJob", "updateDeliveryStatus", "expireDeliveryRequests", "rejectWithdrawal",
  ])("required callable/trigger %s is exported", (name) => {
    expect(exported[name]).toBeDefined();
  });
});
