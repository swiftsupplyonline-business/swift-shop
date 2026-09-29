# Swift Platform Flow Matrix

## Purpose
This document serves as the authoritative, repository-grounded control layer mapping all end-to-end user and system flows across the Swift social-commerce ecosystem (`swiftsupplyonline-business/swift-shop`). It provides precise tracing across Android client modules, Cloud Functions, Firestore schemas, security rules, notification events, web endpoints, admin capabilities, and test suites.

---

## Canonical Architecture

```text
                               ┌────────────────────────────────┐
                               │        SWIFT BACKEND           │
                               │  Firebase Auth + Firestore     │
                               │  Cloud Functions (14 modules)   │
                               │  Storage · Hosting · FCM       │
                               └───────────────┬────────────────┘
                                               │
             ┌──────────────────┬──────────────┼──────────────┬──────────────────┐
             │                  │              │              │                  │
             ▼                  ▼              ▼              ▼                  ▼
      ┌─────────────┐   ┌──────────────┐  ┌─────────────┐  ┌──────────────┐   ┌─────────────┐
      │ Android App │   │ Web Hosting  │  │ Admin Surface│ │ Smart Links  │   │ Next.js Web │
      │ 28 modules  │   │ (hosting/)   │  │ (admin.ts)  │  │ /s/ & /d/    │   │ (web/ dead) │
      │ GREEN       │   │ GREEN live   │  │ AMBER       │  │ GREEN        │   │ BLUE        │
      └─────────────┘   └──────────────┘  └─────────────┘  └──────────────┘   └─────────────┘
```

---

## Actor Model
1. **Buyer / Customer:** Discovers shops and products, manages cart, places orders, selects fulfillment, pays via wallet/MoPay, tracks deliveries, and writes social interactions.
2. **Seller / Merchant:** Creates and manages shops/listings, acknowledges orders, prepares stock, manages delivery requests, and views sales analytics.
3. **Delivery Provider:** Independent logistics merchant offering delivery listings, managing driver rosters (`deliveryProviders/{providerId}/drivers`), and accepting delivery jobs.
4. **Driver:** Authenticated delivery personnel claiming jobs, updating live lat/lng locations, executing pickups, and completing drop-offs.
5. **Admin:** Platform operational authority monitoring system health (`getAdminDashboardStats`), moderating flagged listings (`moderateListing`), and managing disputes/refunds.

---

## Status Definitions
- **GREEN:** Complete, verified in code/tests, and internally consistent.
- **AMBER:** Substantially implemented but has a missing integration, test gap, edge case, or UI polish requirement.
- **RED:** Broken, contradictory, security vulnerability, or competing duplicate authority.
- **GREY:** Designed/expected in product vision but not yet implemented.
- **BLUE:** Exists separately in repository (e.g. Next.js `web/` scaffold) awaiting architectural integration decision.

---

## Identity & Provisioning

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ID-01 | Buyer / Merchant | Register New Account | `AuthScreen.kt` | `auth_route` | `AuthViewModel.kt` | `RegisterUseCase.kt` | `FirebaseAuthRepository.kt` | `auth.ts` (`provisionNewUser`) | `users/{uid}`, `profiles/{uid}`, `wallets/{uid}` | `users/{uid}` allow create if isOwner | `AUTH_USER_CREATED` | Anonymous / Web Auth | `WorkflowFoundationTest.kt` | **GREEN** | `auth.ts` initializes user, profile, and wallet on Auth trigger. |
| ID-02 | Buyer / Merchant | Login / Authenticate | `LoginScreen.kt` | `login_route` | `AuthViewModel.kt` | `LoginUseCase.kt` | `FirebaseAuthRepository.kt` | Firebase Auth SDK | `users/{uid}` | `users/{uid}` allow read if isOwner | `USER_LOGGED_IN` | Web Auth in `app.js` | `AuthValidationTest.kt` | **GREEN** | Firebase Auth token generation verified. |
| ID-03 | Buyer | Register FCM Device | App Startup | Background Service | `MessagingService.kt` | `UpdateFcmTokenUseCase` | `FirebaseMessagingRepository.kt` | `notifications.ts` (`updateFcmToken`) | `users/{uid}/devices/{deviceId}` | Server-only write | `FCM_TOKEN_UPDATED` | N/A | `notifications.test.ts` | **GREEN** | Multi-device FCM registration in `notifications.ts`. |

---

## Profiles & Entitlements

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| PR-01 | User | View / Edit Profile | `ProfileScreen.kt` | `profile_route` | `ProfileViewModel.kt` | `UpdateProfileUseCase.kt` | `FirebaseProfileRepository.kt` | Direct Firestore / `maintenance.ts` | `profiles/{uid}` | `profiles/{uid}` allow write if isOwner & isActive | `PROFILE_UPDATED` | Rendered on Web | `Phase1ContractTest.kt` | **GREEN** | Social counters protected from client forgery. |
| PR-02 | Merchant | Check Merchant Entitlements | `CreateListingGatewayScreen.kt` | `create_listing_gateway` | `CreateListingViewModel.kt` | `CheckListingEligibilityUseCase` | `FirebaseCommerceRepository.kt` | `entitlements.ts` | `entitlements/{uid}`, `merchantUsage/{uid}` | Server-only write | `ENTITLEMENTS_CHECKED` | N/A | `MerchantQuotaTest.kt` | **GREEN** | `TierEntitlements` enforces BASIC (10/shop), PREMIUM (50 total), ELITE (unlimited). |

---

## Shops

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SH-01 | Merchant | Create Shop | `CreateShopScreen.kt` | `create_shop` | `CreateShopViewModel.kt` | `CreateShopUseCase.kt` | `FirebaseCommerceRepository.kt` | `commerce.ts` (`createShop`) | `shops/{shopId}` | `shops` allow create if false (Server-only) | `SHOP_CREATED` | N/A | `CanCreateShopUseCaseTest.kt` | **GREEN** | Enforces tier shop limits (BASIC: 3, PREMIUM: 5, ELITE: unlimited). |
| SH-02 | Customer | Browse Shop Page | `ShopDetailScreen.kt` | `shop/{shopId}` | `ShopDetailViewModel.kt` | `GetShopUseCase.kt` | `FirebaseCommerceRepository.kt` | `publicMarketplace.ts` | `shops/{shopId}`, `listings` | `shops` allow read (public) | N/A | `renderShop` in `app.js` | `W25B_UIContractTest.kt` | **GREEN** | Fully supported on Android and Web (`/shop/{shopId}`). |

---

## Listings

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LS-01 | Merchant | Create Listing | `CreateListingGatewayScreen.kt` | `create_listing` | `CreateListingViewModel.kt` | `CreateListingUseCase.kt` | `FirebaseCommerceRepository.kt` | `commerce.ts` (`createListing`) | `listings/{listingId}` | `listings` allow create if false | `LISTING_CREATED` | N/A | `CreateListingUseCaseTest.kt` | **GREEN** | Verifies quotas, uploads media, and sets stock/pricing. |
| LS-02 | Customer | View Listing Detail | `ListingDetailScreen.kt` | `listing/{listingId}` | `ListingDetailViewModel.kt` | `GetListingUseCase.kt` | `FirebaseCommerceRepository.kt` | `sharePreview.ts` (`renderListingPreview`) | `listings/{listingId}` | `listings` allow read (public) | `LISTING_VIEWED` | `renderListing` in `app.js` | `W25B_UIContractTest.kt` | **GREEN** | Connected to OpenGraph previews and fallback smart link lookup. |

---

## Smart Links

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SL-01 | Anyone | Open Product Smart Link | Deep Link URI | `swiftshop://listing/{id}` | N/A | `GetListingUseCase.kt` | `FirebaseCommerceRepository.kt` | `sharePreview.ts` (`renderListingPreview`) | `listings/{id}` | Public read | N/A | `/listing/{id}` rewrite in `firebase.json` | `BackendVerificationTest.kt` | **GREEN** | Generates OpenGraph HTML with deep links to Android/Web. |
| SL-02 | Anyone | Open Shop Smart Link | Deep Link URI | `swiftshop://shop/{id}` | N/A | `GetShopUseCase.kt` | `FirebaseCommerceRepository.kt` | `sharePreview.ts` (`renderShopPreview`) | `shops/{id}` | Public read | N/A | `/shop/{id}` rewrite in `firebase.json` | `BackendVerificationTest.kt` | **GREEN** | Generates OpenGraph HTML for shop previews. |

---

## Commerce & Checkout

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CM-01 | Customer | Calculate Cart Fees | `CheckoutScreen.kt` | `checkout` | `CheckoutViewModel.kt` | `CalculateOrderFeesUseCase` | `FirebaseCommerceRepository.kt` | `commerce.ts` (`calculateOrderFees`) | `listings` | Server-authoritative calculation | N/A | `renderCheckout` in `app.js` | `commerce.test.ts` | **GREEN** | Recalculates subtotal, delivery fee, and 2.5% platform fee. |
| CM-02 | Customer | Place Order | `CheckoutScreen.kt` | `checkout_confirm` | `CheckoutViewModel.kt` | `PlaceOrderUseCase.kt` | `FirebaseCommerceRepository.kt` | `commerce.ts` (`createOrder`) | `orders/{orderId}`, `reservations` | `orders` allow create if false | `ORDER_CREATED` | `createOrder` in `app.js` | `commerce.test.ts` | **GREEN** | Server-authoritative order creation, inventory stock reservations, and MoPay session initiation. |

---

## Orders & State Machine

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| OR-01 | Merchant | Update Order Status | `OrdersScreen.kt` | `orders` | `OrdersViewModel.kt` | `UpdateOrderStatusUseCase` | `FirebaseCommerceRepository.kt` | `commerce.ts` (`updateOrderStatus`) | `orders/{orderId}` | Server-only transition | `ORDER_STATUS_CHANGED` | Admin Moderate | `commerce.test.ts` | **GREEN** | Enforces `SELLER_TRANSITIONS` (`CONFIRMED` -> `PROCESSING` -> `READY`). |
| OR-02 | Customer | Confirm Receipt / Escrow Release | `OrderDetailScreen.kt` | `order/{id}` | `OrdersViewModel.kt` | `ConfirmDeliveryUseCase` | `FirebaseCommerceRepository.kt` | `commerce.ts` (`confirmDelivery`) / `finance.ts` | `orders`, `wallets`, `ledgerEntries` | Server-only transition | `ESCROW_RELEASED` | Admin Dispute | `commerce.test.ts` | **GREEN** | Confirms order receipt, releases escrow funds, and credits merchant wallet. |

---

## Fulfillment & Delivery

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DL-01 | Merchant / Buyer | Request Delivery | `IncomingDeliveryRequestsScreen.kt` | `delivery_requests` | `IncomingDeliveryRequestsViewModel.kt` | `RequestDeliveryUseCase` | `FirebaseDeliveryRepository.kt` | `logistics.ts` (`requestDelivery`) | `deliveryRequests/{requestId}` | `deliveryRequests` allow create if false | `DELIVERY_REQUESTED` | N/A | `logistics.test.ts` | **GREEN** | Creates delivery request with 120-second lease window. |
| DL-02 | Driver | Claim Delivery Job | `DeliveryTrackingScreen.kt` | `delivery_track` | `DeliveryTrackingViewModel.kt` | `AuthorizeDriverUseCase` | `FirebaseDeliveryRepository.kt` | `logistics.ts` (`authorizeDriver`, `updateDeliveryStatus`) | `deliveryRoutes`, `deliveryProviders` | Driver ID matches `auth.uid` | `DELIVERY_ASSIGNED` | N/A | `logistics.test.ts` | **GREEN** | Server-authoritative driver authorization and route tracking via OpenStreetMap (`osmdroid`). |

---

## Wallet & Finance

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FN-01 | Customer / Merchant | View Wallet & Ledger | `WalletScreen.kt` | `wallet` | `WalletViewModel.kt` | `GetWalletUseCase.kt` | `FirebaseWalletRepository.kt` | `finance.ts` | `wallets/{uid}`, `ledgerEntries` | `wallets` allow read if isOwner | N/A | N/A | `MoneyAmountTest.kt` | **GREEN** | Read-only ledger view. Immutable ledger entries written server-side. |
| FN-02 | User | P2P Transfer / Withdrawal | `WalletScreen.kt` | `wallet_transfer` | `WalletViewModel.kt` | `TransferFundsUseCase` | `FirebaseWalletRepository.kt` | `finance.ts` (`initiateP2PTransfer`, `initiateWithdrawal`) | `wallets`, `walletTransactions` | Server-only mutation | `TRANSFER_COMPLETED` | Admin Approval | `commerce.test.ts` | **GREEN** | Atomic transaction balance debit/credit with double-entry accounting. |

---

## Social & Feed

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SC-01 | User | Publish Post / Like / Comment | `HomeScreen.kt` | `home_posts` | `HomeViewModel.kt` | `CreatePostUseCase.kt` | `FirebaseSocialRepository.kt` | `social.ts` (`publishPost`, `likePost`, `createPostComment`) | `posts`, `comments`, `likes` | `posts` allow create if false | `POST_PUBLISHED` | N/A | `ArchitectureMapperTest.kt` | **GREEN** | Atomic post/listing counter synchronization (`likeCount`, `commentCount`). |
| FD-01 | Customer | Browse Main Feed | `HomeScreen.kt` | `home_feed` | `HomeViewModel.kt` | `GetFeedUseCase.kt` | `FirebaseFeedRepository.kt` | `social.ts` | `posts`, `listings`, `reels` | Public read | N/A | `renderBrowse` in `app.js` | `W25B_UIContractTest.kt` | **GREEN** | Tabbed feed (`Shop` \| `Posts` \| `Reels`) with cursor pagination. |

---

## Notifications

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NT-01 | System | Dispatch FCM Push Notification | App Background | N/A | N/A | N/A | `FirebaseMessagingRepository.kt` | `notifications.ts` (`sendNotification`) | `notificationEvents`, `users/{uid}/devices` | Server-only write | FCM Multicast | N/A | `notifications.test.ts` | **GREEN** | Multi-device fan-out, 30s claim lock, per-device accounting, and token auto-deactivation. |

---

## Search & Discovery

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SR-01 | Customer | Universal Search | `SearchScreen.kt` | `search` | `SearchViewModel.kt` | `SearchListingsUseCase` | `FirebaseCommerceRepository.kt` | `publicMarketplace.ts` | `listings`, `shops`, `users`, `posts` | Public read | N/A | Header search in `app.js` | `W25B_UIContractTest.kt` | **GREEN** | Multi-entity search filtering listings and shops in real-time. |

---

## Admin Surface

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AD-01 | Admin | View Operational Dashboard | N/A (Missing UI) | N/A | N/A | N/A | N/A | `admin.ts` (`getAdminDashboardStats`) | `users`, `shops`, `listings`, `orders` | `isAdmin()` check | N/A | Admin API callable | `security-rules.test.ts` | **AMBER** | Backend callable implemented and protected by `isAdmin()`; client UI dashboard missing. |
| AD-02 | Admin | Moderate / Flag Listing | N/A (Missing UI) | N/A | N/A | N/A | N/A | `admin.ts` (`moderateListing`) | `listings`, `auditLogs` | `isAdmin()` check | `LISTING_MODERATED` | Admin API callable | `security-rules.test.ts` | **AMBER** | Backend callable writes audit log; client UI dashboard missing. |

---

## AI Marketplace Assistant

| Flow ID | Actor | User Action | Android UI | Navigation | ViewModel | Domain Use Case | Repository | Function / Engine | Firestore Collection | Security Rule | Event / Notification | Web / Admin | Tests | Status | Evidence & Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AI-01 | Customer | Natural Language Search Assistant | `SearchScreen.kt` | `search_ai` | `SearchViewModel.kt` | `SearchAssistantUseCase` | `FirebaseCommerceRepository.kt` | `aiAssistant.ts` (`searchAssistant`) | `listings` | Public read | N/A | Web AI Assistant | `commerce.test.ts` | **GREEN** | Translates natural language shopping queries into real marketplace search constraint queries. |
| AI-02 | Merchant | AI Listing Generator | `CreateListingGatewayScreen.kt` | `ai_generator` | `CreateListingViewModel.kt` | `GenerateListingDetailsUseCase` | `FirebaseCommerceRepository.kt` | `aiAssistant.ts` (`generateListingDetails`) | N/A | `auth.uid` check | N/A | N/A | `commerce.test.ts` | **GREEN** | Generates clean titles, descriptions, and tags from merchant notes. |

---

## Remediation Priorities

1. **P0 (Platform Correctness):** All P0 items (order state transitions, fee calculation, payment recovery, driver authorization) are **GREEN** and verified.
2. **P1 (Production Readiness):** MoPay live production API key secret configuration in Firebase Secrets Manager before live launch.
3. **P2 (Important Integration - AMBER):** Build lightweight Web/Android Admin Operational Dashboard UI for `getAdminDashboardStats` and `moderateListing`.
4. **P3 (Future Capability - GREY/BLUE):** Reconcile or archive Next.js `web/` scaffold in favor of the active Firebase Hosting `hosting/` surface.
