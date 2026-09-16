# PROPOSED RED-TIER CHANGES (DRAFT)

This file contains proposed modifications for locked/RED-tier files. These changes have NOT been applied.

---

## 1. BUG 1 FIX — Actionable "Listing Not Found" Errors

**Issue:** A single deleted listing in a cart causes the entire checkout to fail with a generic "Listing {id} not found" error, making it hard for the buyer to fix their cart.

**Current Code (`functions/src/commerce.ts`):**
```typescript
// Lines 28-29
const listingDoc = await db.collection("listings").doc(item.listingId).get();
if (!listingDoc.exists) throw new HttpsError("not-found", `Listing ${item.listingId} not found`);
```

**Proposed Fix (Server-side):**
We will implement an actionable error that names the specific item. This is chosen over "skipping" to maintain financial consistency without changing the API response schema.

```diff
--- a/functions/src/commerce.ts
+++ b/functions/src/commerce.ts
@@ -26,7 +26,11 @@
 
     for (const item of items) {
         const listingDoc = await db.collection("listings").doc(item.listingId).get();
-        if (!listingDoc.exists) throw new HttpsError("not-found", `Listing ${item.listingId} not found`);
+        if (!listingDoc.exists) {
+            // Name the specific item in the error so the client can highlight or remove it
+            const itemTitle = item.title || "Unknown Item";
+            throw new HttpsError("not-found", `Item "${itemTitle}" is no longer available. Please remove it from your cart.`);
+        }
         const listing = listingDoc.data()!;
         subtotal += (listing.priceMinorUnits || 0) * (item.quantity || 1);
```

*(Identical change to be applied inside the `createOrder` transaction at lines 98-99)*

---

## 2. DELIVERY SHOP-MISMATCH FIX

**Issue:** Buyers can currently select a delivery provider from an unrelated shop because the client doesn't filter the list and the server doesn't validate the relationship.

### A. Server-side Validation (Security)
**Current Code (`functions/src/commerce.ts`):**
```typescript
// Lines 47-51 in calculateOrderFees
const deliveryListing = deliveryListingDoc.data()!;
if (deliveryListing.listingType !== "DELIVER") throw new HttpsError("failed-precondition", "Invalid delivery listing type");
if (!deliveryListing.isAvailable) throw new HttpsError("failed-precondition", "Delivery service is currently unavailable");
deliveryFee = deliveryListing.priceMinorUnits || 0;
```

**Proposed Fix:**
```diff
--- a/functions/src/commerce.ts
+++ b/functions/src/commerce.ts
@@ -48,6 +48,7 @@
             const deliveryListing = deliveryListingDoc.data()!;
             if (deliveryListing.listingType !== "DELIVER") throw new HttpsError("failed-precondition", "Invalid delivery listing type");
             if (!deliveryListing.isAvailable) throw new HttpsError("failed-precondition", "Delivery service is currently unavailable");
+            if (deliveryListing.shopId !== shopId) throw new HttpsError("invalid-argument", "Selected delivery provider does not belong to this shop.");
             deliveryFee = deliveryListing.priceMinorUnits || 0;
         } else {
```

*(Identical check for `dlData.shopId !== shopId` in `createOrder` at lines 132-133)*

### B. Client-side Filtering (UX)

**Proposed Fix (`domain/commerce/CommerceUseCases.kt`):**
```diff
--- a/domain/commerce/src/main/java/com/swiftshop/domain/commerce/CommerceUseCases.kt
+++ b/domain/commerce/src/main/java/com/swiftshop/domain/commerce/CommerceUseCases.kt
@@ -95,7 +95,7 @@
 
     // Listings
     fun getShopListings(shopId: String, page: Int, pageSize: Int): Flow<List<Listing>>
-    fun getDeliveryListings(): Flow<List<Listing>> = kotlinx.coroutines.flow.emptyFlow()
+    fun getDeliveryListings(shopId: String): Flow<List<Listing>> = kotlinx.coroutines.flow.emptyFlow()
     suspend fun getListing(listingId: String): Result<Listing>
```

**Proposed Fix (`data/firebase/FirebaseCommerceRepository.kt`):**
```diff
--- a/data/firebase/src/main/java/com/swiftshop/data/firebase/FirebaseCommerceRepository.kt
+++ b/data/firebase/src/main/java/com/swiftshop/data/firebase/FirebaseCommerceRepository.kt
@@ -80,9 +80,10 @@
         awaitClose { subscription.remove() }
     }
 
-    override fun getDeliveryListings(): Flow<List<Listing>> = callbackFlow {
+    override fun getDeliveryListings(shopId: String): Flow<List<Listing>> = callbackFlow {
         val subscription = firestore.collection("listings")
             .whereEqualTo("listingType", "DELIVER")
+            .whereEqualTo("shopId", shopId)
             .whereEqualTo("isAvailable", true)
             .addSnapshotListener { snapshot, _ ->
```


---

## 3. FALLBACK DELIVERY-LISTING ORDERING GAP (proposed 2026-09-16)

**Issue:** When `requiresDelivery` is true, no `selectedDeliveryListingId` is given, and a shop has 2+ DELIVER listings, both `calculateOrderFees` and `createOrder` fall back to `deliverySnap.docs[0]` with no defined ordering. Not a security issue (already shop-scoped), but "which delivery option did the buyer get" is effectively undefined.

**Proposed Fix:** add a deterministic ordering — cheapest first:

```diff
--- a/functions/src/commerce.ts
+++ b/functions/src/commerce.ts
@@ -50,6 +50,7 @@
             const deliverySnap = await db.collection("listings")
                 .where("shopId", "==", shopId)
                 .where("listingType", "==", "DELIVER")
                 .where("isAvailable", "==", true)
+                .orderBy("priceMinorUnits", "asc")
                 .get();
 
             if (deliverySnap.empty) {
```

*(Identical addition to the equivalent query inside the `createOrder` transaction.)*

**Caveat before implementing:** adding `.orderBy()` alongside three equality `.where()` filters will very likely require a new Firestore composite index (`shopId + listingType + isAvailable + priceMinorUnits`). Confirm against `docs/firestore.indexes.json` first — an undeployed index means the query fails at runtime.