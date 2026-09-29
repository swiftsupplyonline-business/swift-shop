# Swift Caller & Contract Matrix

## Purpose
This document provides the technical, code-level caller tracing, Firestore schema contract definitions, security rule boundaries, and notification event mappings across the Swift platform. It prevents breaking changes by establishing explicit caller dependencies before any function refactoring or migration.

---

## Caller Audit Matrix

| Symbol / Function | Defined In | Called By | Layer | Canonical? | Legacy / Alt? | Contract / Input | Output | Firestore Mutated | Security Rule | Tests | Migration / Action Needed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `calculateOrderFees` | `functions/src/commerce.ts` | `CheckoutViewModel.kt`, `hosting/app.js` | Commerce | **YES** | N/A | `{ items, requiresDelivery, address? }` | `{ subtotalMinorUnits, deliveryFeeMinorUnits, platformFeeMinorUnits, totalMinorUnits }` | Reads `listings` | Server-authoritative callable | `commerce.test.ts` | **Canonical.** Revalidates prices directly from Firestore listings. |
| `createOrder` | `functions/src/commerce.ts` | `CheckoutViewModel.kt`, `hosting/app.js` | Commerce | **YES** | `createPurchaseOrder` (proposed) | `{ items, requiresDelivery, address?, paymentMethod, idempotencyKey }` | `{ success, orderId, paymentUrl? }` | `orders/{orderId}`, `reservations` | `orders` allow create if false | `commerce.test.ts` | **Canonical.** Creates pending order, stock reservation, and MoPay session. |
| `requestDelivery` | `functions/src/logistics.ts` | `IncomingDeliveryRequestsViewModel.kt` | Delivery | **YES** | `createDeliveryRequest` | `{ merchantId, orderId, deliveryListingId }` | `{ requestId, status: "WAITING" }` | `deliveryRequests/{requestId}` | `deliveryRequests` create if false | `logistics.test.ts` | **Canonical.** Creates delivery request with 120s merchant response lease. |
| `respondToDeliveryRequest` | `functions/src/logistics.ts` | `IncomingDeliveryRequestsViewModel.kt` | Delivery | **YES** | N/A | `{ requestId, response: "ACCEPTED"|"DECLINED" }` | `{ success, requestId, status }` | `deliveryRequests/{requestId}`, `orders` | Server-only write | `logistics.test.ts` | **Canonical.** Merchant accepts or declines pending delivery request. |
| `authorizeDriver` | `functions/src/logistics.ts` | `DeliveryTrackingViewModel.kt` | Delivery | **YES** | N/A | `{ providerId, driverId }` | `{ success, authorized }` | `deliveryProviders/{providerId}/drivers/{driverId}` | Driver ID matches `auth.uid` | `logistics.test.ts` | **Canonical.** Server-authoritative driver authorization. |
| `getAdminDashboardStats` | `functions/src/admin.ts` | Admin Surface | Admin | **YES** | N/A | `{}` | `{ totalUsers, totalShops, totalListings, totalOrders }` | Reads collections | `isAdmin()` claim check | `security-rules.test.ts` | **Canonical.** Operational stats guarded by admin token claim. |
| `moderateListing` | `functions/src/admin.ts` | Admin Surface | Admin | **YES** | N/A | `{ listingId, action: "FLAG"|"ARCHIVE"|"APPROVE", reason }` | `{ success, listingId, moderationStatus }` | `listings/{id}`, `auditLogs` | `isAdmin()` claim check | `security-rules.test.ts` | **Canonical.** Moderates flagged listings and records audit logs. |
| `searchAssistant` | `functions/src/aiAssistant.ts` | `SearchViewModel.kt`, `hosting/app.js` | AI | **YES** | N/A | `{ query, maxPriceMinorUnits? }` | `{ success, query, matchedCount, results }` | Reads `listings` | Public read | `commerce.test.ts` | **Canonical.** Natural language query translator into real listing queries. |
| `generateListingDetails` | `functions/src/aiAssistant.ts` | `CreateListingViewModel.kt` | AI | **YES** | N/A | `{ rawTitle, rawNotes, category }` | `{ suggestedTitle, suggestedDescription, suggestedTags }` | N/A | Authenticated `auth.uid` | `commerce.test.ts` | **Canonical.** AI merchant helper formatting listing metadata. |

---

## Firestore Schema Contracts

| Collection | Primary Key | Owner / Writer | Reader | Key Fields | Foreign Keys | Security Rule Protection |
|---|---|---|---|---|---|---|
| `users` | `uid` | Server (`provisionNewUser`) | Owner / Admin | `uid`, `email`, `accountStatus`, `tier`, `isAdmin` | N/A | `allow create/update: if isOwner(uid)` (Privileged fields guarded) |
| `profiles` | `uid` | User (isOwner & isActive) | Signed-in users | `displayName`, `photoUrl`, `followerCount`, `shopCount` | `uid` -> `users.uid` | Counter fields protected from client update |
| `shops` | `shopId` | Server (`createShop`) | Public | `id`, `ownerId`, `name`, `logoUrl`, `isVerified` | `ownerId` -> `users.uid` | `allow create: if false` (Server-only shop creation) |
| `listings` | `listingId` | Server (`createListing`) / Owner | Public | `id`, `shopId`, `sellerId`, `title`, `priceMinorUnits`, `stockQuantity` | `shopId` -> `shops.id` | `allow create: if false`, owner updates restricted from mutating price/stock |
| `orders` | `orderId` | Server (`createOrder`) | Buyer / Seller / Admin | `id`, `buyerId`, `sellerId`, `totalMinorUnits`, `status`, `paymentMethod` | `buyerId` -> `users.uid` | `allow create/update: if false` (Server-only) |
| `deliveryRequests` | `requestId` | Server (`requestDelivery`) | Requester / Merchant | `id`, `orderId`, `requesterId`, `merchantId`, `status`, `expiresAt` | `orderId` -> `orders.id` | `allow create/update: if false` (Server-only 120s lease) |
| `deliveryRoutes` | `routeId` | Server (`assignDriver`) | Buyer / Driver / Admin | `id`, `orderId`, `driverId`, `status`, `driverCurrentLocationLat/Lng` | `driverId` -> `users.uid` | Driver can update location coordinates only |
| `deliveryProviders` | `providerId` | Merchant / Provider | Signed-in users | `id`, `name`, `ownerId`, `isAuthorized` | `ownerId` -> `users.uid` | `drivers` subcollection writable by provider owner only |
| `wallets` | `uid` | Server (`finance.ts`) | Owner / Admin | `uid`, `balanceMinorUnits`, `escrowBalanceMinorUnits` | `uid` -> `users.uid` | `allow write: if false` (Server-only financial ledger) |
| `ledgerEntries` | `entryId` | Server (`finance.ts`) | Admin / Debit / Credit Account | `id`, `debitAccount`, `creditAccount`, `amountMinorUnits`, `type` | `debit/creditAccount` -> `wallets.uid` | `allow write: if false` (Immutable double-entry accounting) |
| `walletTransactions` | `txId` | Server (`finance.ts`) | Owner | `id`, `userId`, `type`, `amountMinorUnits`, `status` | `userId` -> `users.uid` | `allow write: if false` |
| `conversations` | `conversationId` | Participants | Participant Users | `id`, `participantIds`, `lastMessageText`, `updatedAt` | `participantIds` -> `users.uid` | Must be in `participantIds` |
| `messages` | `messageId` | Sender User | Participant Users | `id`, `conversationId`, `senderId`, `text`, `createdAt` | `conversationId` -> `conversations.id` | Sender ID must match `auth.uid` |
| `posts` | `postId` | Server (`publishPost`) | Public | `id`, `authorId`, `text`, `mediaUrls`, `likeCount` | `authorId` -> `users.uid` | `allow create: if false` (Server counter sync) |
| `advertisingCampaigns` | `campaignId` | Owner User | Owner / Admin | `id`, `ownerId`, `status`, `budgetMinorUnits`, `targetCategory` | `ownerId` -> `users.uid` | Status updates restricted to `PAUSED` |
| `notificationEvents` | `eventId_userId` | Server (`sendNotification`) | Admin | `userId`, `payload`, `status`, `deviceAccounting`, `updatedAt` | `userId` -> `users.uid` | `allow write: if false` (Server notification accounting) |
| `users/{uid}/devices` | `deviceId` | User (`updateFcmToken`) | Owner / Server | `token`, `platform`, `isActive`, `updatedAt` | `uid` -> `users.uid` | Writable by device owner via `updateFcmToken` callable |

---

## Notification Event Triggers & FCM Contracts

```text
Message Created ("messages/{messageId}")
  ↓
notifyOnMessage
  ↓
Resolves conversation participantIds (excluding senderId)
  ↓
sendNotification(recipientId, payload, eventId)
  ↓
Fetches active devices from users/{recipientId}/devices where isActive == true
  ↓
Multicast send via admin.messaging().sendEachForMulticast()
  ↓
Updates per-device status in notificationEvents/{eventId}_{recipientId}
```

---

## Canonical Authority Matrix

| State Domain | Canonical System / Module | Enforcement Pattern |
|---|---|---|
| **User Identity & Claims** | Firebase Auth + `auth.ts` | Server trigger `provisionNewUser` creates user records. |
| **Listing Price & Stock** | `commerce.ts` (`createListing`, `updateListing`) | Client cannot modify price/stock directly via Firestore rules. |
| **Purchase Order Totals** | `commerce.ts` (`calculateOrderFees`, `createOrder`) | Recalculated server-side directly from listing documents. |
| **Inventory Stock Lock** | `reservations.ts` (`reserveInventory`) | Atomic reservation locks stock during checkout. |
| **Wallet Balance & Ledger** | `finance.ts` (`processTransfer`, `releaseEscrow`) | Immutable double-entry ledger entries in `ledgerEntries`. |
| **Delivery Authorization** | `logistics.ts` (`authorizeDriver`, `assignDriver`) | Server checks driver ownership in `deliveryProviders`. |
| **Notification Accounting** | `notifications.ts` (`sendNotification`) | Atomic claim lock (30s lease) in `notificationEvents`. |

---

## Golden End-to-End User Flow Execution

```text
[Customer / Buyer]
  1. Opens Smart Link (/listing/{id}) -> Resolved by sharePreview.ts / hosting/app.js
  2. Adds item to Cart -> Calculated via calculateOrderFees (server-authoritative subtotal + fees)
  3. Selects Fulfillment -> "Standard Delivery" collects delivery address
  4. Places Order -> createOrder validates stock, creates reservations, creates order in PENDING
  5. Initiates Payment -> MoPay session created or Swift Wallet authorized
  6. Order Status -> Transitions to CONFIRMED and sends notifyOnOrderStatusChange push notification

[Seller / Merchant]
  7. Receives Notification -> Order update alert
  8. Acknowledges & Prepares -> Order status updated to PROCESSING -> READY
  9. Requests Delivery -> logistics.ts requestDelivery creates deliveryRequest (120s lease)
 10. Receives Driver Claim -> Provider/Driver accepts delivery job

[Delivery Driver]
 11. Driver Claims Job -> logistics.ts authorizeDriver verifies driver identity
 12. Pickup & In Transit -> updateDeliveryLocation updates live driver Lat/Lng on deliveryRoutes
 13. Delivery Completed -> completeDelivery releases escrow hold

[Financial Settlement]
 14. Escrow Release -> finance.ts releaseEscrow debits escrow and credits merchant wallet
 15. Ledger Entry -> Immutable transaction record written to ledgerEntries
```
