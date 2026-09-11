# SWIFT MASTER PROJECT BLUEPRINT

## 1. Executive Summary
The Swift Ecosystem is a multi-platform marketplace and business management platform designed for the Lesotho market and beyond. It utilizes a modular Clean Architecture with a server-authoritative financial core, double-entry ledger, and a social-discovery layer. The ecosystem consists of three primary applications (Shop, Admin, Web) sharing a common Firebase-backed infrastructure.

## 2. Swift Vision
To provide a decentralized yet governed digital economy where businesses (Shops) can offer diverse services (Listings) to citizens, backed by a secure, real-time financial system (Wallet/Ledger).

## 3. Swift City Architecture
Swift is conceptually represented as a digital city:
*   **Swift Shop**: The public marketplace and citizen-facing environment.
*   **Swift Admin**: The city administration and operational control center.
*   **Firebase**: The underlying municipal infrastructure (Electricity, Water, Roads).
*   **Shops**: Businesses operating and paying rent/fees inside the city.
*   **Listings**: Diverse business/service offerings exposed to citizens.
*   **Feed**: The city's social square and discovery layer.
*   **Wallet**: The financial infrastructure and digital currency (LSL) available to citizens.
*   **Ledger**: The authoritative, immutable financial record kept by the city treasury.
*   **Advertising**: The city's commercial promotion infrastructure.
*   **Web**: An alternative entrance for guests and high-volume commerce.
*   **Swift Maps & Geospatial**: The city's navigation, cartography, and location-intelligence layer.

### 3.1 Canonical Ecosystem Structure
```text
SWIFT ECOSYSTEM
│
├── IDENTITY & PROFILES
│   ├── Buyer
│   ├── Seller
│   └── Delivery Provider
│
├── COMMERCE
│   ├── Shops
│   ├── Listings (Physical, Food, Service, Bulk)
│   │    ↓
│   └── Orders (Purchase, Booking, Request)
│
├── FINANCIAL SYSTEM
│   ├── Payments
│   ├── Wallets
│   └── Double-Entry Ledger
│
├── FULFILLMENT & TRACKING
│   ├── Physical Delivery
│   ├── Service Fulfillment
│   ├── Appointments
│   └── Form / Workflow Fulfillment
│
└── SWIFT MAPS & GEOSPATIAL
    ├── Base Map
    ├── Swift Commercial Layer
    ├── Drop Your Pin
    ├── Fulfillment Locations
    ├── Delivery Tracking Visualization
    └── Role-Specific Map Views
```

## 4. Ecosystem Applications

### 4.1 Swift Shop
*   **Role**: Primary consumer interface.
*   **Capabilities**: Social feed, Shop discovery, Multi-model Listings, Cart, Checkout, P2P Transfers, Wallet management.
*   **Status**: `IMPLEMENTED` / `COMPILED` (Static logic verified) / `RUNTIME VERIFIED` (Initial launch pass).

### 4.2 Swift Admin
*   **Role**: Operational control center.
*   **Responsibilities**: User management, Moderation, Withdrawal approvals, Ledger inspection, System health.
*   **Status**: `IMPLEMENTATION STATUS: INCOMPLETE`.

### 4.3 Swift Web
*   **Role**: Web-based discovery and commerce.
*   **Capabilities**: Guest checkout, SEO-optimized listings, Merchant dashboards.
*   **Status**: `PLANNED`.

## 5. Backend & Infrastructure

### 5.1 Firebase
*   **Provider**: Shared ecosystem infrastructure.
*   **Environment**: `swift-dev-3d3ae` (DEV).
*   **Authority**: Sole source of truth for Auth and Data.

### 5.2 Firestore
*   **Role**: Primary transactional database.
*   **Structure**: Collection-per-entity (Users, Profiles, Shops, Listings, etc.).
*   **Status**: `LOCKED`.

### 5.3 Cloud Functions (v2)
*   **Role**: Authoritative business logic ("The Swift Engine").
*   **Key Functions**: `createOrder`, `createListing`, `initiateDeposit`, `initiateWithdrawal`, `syncProfileCounters`.
*   **Status**: `IMPLEMENTED` (Core logic).

### 5.4 Authentication
*   **Methods**: Email/Password, Phone (Planned), Google.
*   **Claims**: `admin` (bool), `role` (string), `active` (bool).
*   **Status**: `IMPLEMENTED`.

### 5.5 Storage
*   **Role**: Media hosting (Images, Video).
*   **Policy**: Direct-to-client upload, server-side processing (Planned).

### 5.6 Notifications
*   **Role**: Transactional and social alerts (FCM).
*   **Status**: `IMPLEMENTED` (Infrastructure).

## 6. Canonical Domain Model

### Users
*   **Path**: `users/{uid}` (Private).
*   **Fields**: Email, Phone, AccountStatus, Tier, Metadata.
*   **Authority**: Auth System.

### Profiles
*   **Path**: `profiles/{uid}` (Public-ish).
*   **Fields**: DisplayName, Bio, Stats (followerCount, shopCount, etc.), Tier, Location (Snapshot).
*   **Authority**: Server (Counters).

### Shops
*   **Path**: `shops/{shopId}`.
*   **Fields**: OwnerId, Name, Category, Tags, Rating, listingCount, location (GeoPoint), locationAddress.
*   **Authority**: Merchant System.

### Listings
*   **Path**: `listings/{listingId}`.
*   **Logical Model**: Identity, Ownership, Presentation, ListingType, Category, Pricing, FulfillmentOptions, Location, TypeAttributes, Availability, Status.
*   **Authority**: Commerce System.

### Posts / Reels
*   **Path**: `posts/{postId}`.
*   **Fields**: AuthorId, MediaUrls, Caption, Engagement Stats.
*   **Authority**: Social System.

### Orders
*   **Path**: `orders/{orderId}`.
*   **Logical Model**: Identity, Source (Listing), Type, Participants, ListingSnapshot, Payload, Fulfillment, Financial, Inventory/Capacity, Lifecycle, Audit.
*   **Authority**: Order Authority (Swift Engine). Coordination with Finance/Logistics/Inventory.

### Fulfillment
*   **Path**: `fulfillment/{fulfillmentId}`.
*   **Fields**: OrderId, Type (PHYSICAL_DELIVERY, SERVICE, APPOINTMENT, FORM_WORKFLOW), Status, OperationalMetadata.
*   **Authority**: Logistics System.
*   **Status**: `NOT IMPLEMENTED / BLUEPRINT ONLY`.

### Appointments / Forms
*   **Role**: Extended Listing workflows.
*   **Status**: `PLANNED`.

### Wallets
*   **Path**: `wallets/{uid}`.
*   **Fields**: availableBalance, pendingBalance (MoneyAmount).
*   **Authority**: Treasury (Server).

### Ledger
*   **Path**: `ledgerEntries/{entryId}`.
*   **Fields**: debitAccount, creditAccount, amount, reference.
*   **Authority**: Immutable Treasury.

### Advertising
*   **Path**: `advertisingCampaigns/{campaignId}`.
*   **Fields**: budget, status, targeting.
*   **Status**: `PARTIAL`.

### Swift Maps & Geospatial (Subsystem)
*   **Status**: `BLUEPRINT ONLY / NOT IMPLEMENTED`.
*   **Role**: Visualization and Interaction layer for geospatial data.

## 7. Listing System

### 7.1 Listing Philosophy
Listings are the canonical definition of commercial, service, supply, or operational offerings. A Listing is published by a Seller/Author; an Order is the instantiated response to that Listing.

### 7.2 Logical Listing Model
The Listing domain model is organized into the following logical groups:
*   **Identity**: Unique identifiers and canonical references.
*   **Ownership**: shopId, sellerId, and merchant association.
*   **Common Presentation**: title, description, media, tags.
*   **ListingType**: Core taxonomy (PHYSICAL_ITEM, PREPARED_FOOD, etc.).
*   **Category**: Market segment and discovery classification.
*   **Pricing**: Base price, variants, and unit of measure.
*   **FulfillmentOptions**: Delivery, Pickup, On-site, Remote.
*   **Location**: Pickup location (GeoPoint) and landmark-aware addressing.
*   **TypeAttributes**: Polymorphic attributes specific to the ListingType.
*   **Availability**: stockQuantity, slots, or capacity flags.
*   **Status**: isAvailable, moderationState, visibility.

### 7.3 Core Listing Types (Maseru-First)
*   **PHYSICAL_ITEM**: Apparel, electronics, boutique goods. Includes SKU, variants, stock, measurements.
*   **PREPARED_FOOD**: Fast food, catering, bakery. Includes prep time, dietary flags, add-ons.
*   **BOOKABLE_SERVICE**: Hair/beauty, car wash, repairs. Includes duration, slots, service location.
*   **BULK_SUPPLY**: Construction material, wholesale agri. Includes MOQ, unit of measure, tiered pricing.
*   **DELIVERY_SERVICE**: Extensible type for publishing logistics capabilities.

### 7.4 Implementation Strategy
The architecture uses a safe evolution strategy:
`Firestore → DTO → Mapper → Domain Listing → Use Cases → UI`
Existing flat fields (shopId, title, etc.) are preserved for compatibility.

### 7.5 Listing → Shop Relationship
*   A Listing MUST belong to exactly one Shop.
*   Merchant ownership is associated via the Shop.
*   `Visit Shop` is a mandatory CTA.

### 7.3 Listing Creation
*   Requires valid Tier (Basic: 3 free).
*   Server-side validation of limits.

### 7.4 Media
*   Supports multiple images and video clips.
*   Carousel presentation.

### 7.5 Commerce
*   Buy Now / Add to Cart.
*   Authoritative fee calculation.

### 7.6 Appointments
*   `PLANNED` workflow for service-based listings.

### 7.7 Forms
*   `PLANNED` dynamic forms for applications/registrations.

### 7.8 Categories
*   `CANONICAL DECISION`: Listing inherits Shop category by default.

### 7.9 Tags
*   Searchable metadata inherited from Shop and defined at Listing level.

### 7.10 Discovery
*   Appear in Feed, Search, and Profiles.

### 7.11 Visit Shop
*   `ARCHITECTURAL REQUIREMENT`: Every Listing detail page must link to its parent Shop.

### 7.12 Related Listings
*   `PLANNED`: Discoverability based on category/tags.

## 8. Order Architecture

### 8.1 Listing-to-Order Relationship
An Order is an authoritative instantiated response to a Listing. One Listing may produce many Orders. Every Order must identify its originating Listing via `sourceListingId`.

### 8.2 Listing Snapshot
To preserve transactional integrity, an Order captures a snapshot of the Listing state at the moment of creation. This prevents historical transaction meaning from being altered by subsequent Listing edits.
*   **Snapshot Data**: title, listingType, pricing, variant, seller identity, fulfillment requirements.

### 8.3 Order Types (Mapping)
`ListingType` defines what is published; `OrderType` defines the workflow instantiated.
*   `PHYSICAL_ITEM` → `PRODUCT_PURCHASE`
*   `PREPARED_FOOD` → `FOOD_ORDER`
*   `BOOKABLE_SERVICE` → `SERVICE_BOOKING`
*   `BULK_SUPPLY` → `BULK_PURCHASE`
*   `DELIVERY_SERVICE` → `DELIVERY_REQUEST`

### 8.4 Order Payload
Orders use controlled type-specific payloads within the canonical `orders/{orderId}` collection.
*   **PRODUCT_PURCHASE**: quantity, unit price, variant.
*   **FOOD_ORDER**: items, add-ons, prep/schedule, delivery requirements.
*   **SERVICE_BOOKING**: service, requested date/time, duration, location.
*   **BULK_PURCHASE**: quantity, UOM, pricing tier.
*   **DELIVERY_REQUEST**: pickup, destination, package info, recipient, landmarks.

### 8.5 Participant Model
Orders support role-aware participants. A single user may hold multiple roles.
*   **Roles**: REQUESTER, LISTING_AUTHOR, SELLER, SERVICE_PROVIDER, DELIVERY_PROVIDER, RECIPIENT, ADMIN.

### 8.6 Order Perspectives
The canonical Order supports multiple role-aware views/projections:
*   **Buyer/Requester**: "My Order" (Orders I placed).
*   **Seller/Author**: "Order Received" (Orders for my listings).
*   **Service/Delivery Provider**: "Booking/Request" (Assigned work).
*   **Admin**: "Canonical Transaction View".

### 8.7 Authority Separation
The Order coordinates across specialized authoritative subsystems:
*   **Order Authority**: The Swift transaction engine.
*   **Financial Authority**: Treasury and Immutable Ledger.
*   **Payment Authority**: Authoritative verification.
*   **Inventory Authority**: Stock and capacity management.
*   **Fulfillment Authority**: Logistics and execution status.

### 8.8 State Separation
The following states are independent and must not be collapsed:
*   `OrderStatus`: Overall workflow state (e.g., CONFIRMED, COMPLETED).
*   `PaymentStatus`: Financial state (e.g., PENDING, PAID, REFUNDED).
*   `FulfillmentStatus`: Operational state (e.g., PREPARING, IN_TRANSIT, DELIVERED).
*   `SettlementStatus`: Ledger state (e.g., ESCROW_HOLD, RELEASED).
*   `InventoryStatus`: Capacity state (e.g., RESERVED, COMMITTED).

### 8.9 Existing Orders
Historical Orders must be compatibility-mapped only where source listing, participants, and financial semantics can be reliably established.

## 9. Commerce & Checkout
*   **Flow**: Cart -> Address -> Payment Selection -> Confirmation.
*   **Calculation**: Android estimates; Cloud Function determines authoritative fees.
*   **Platform Fee**: 1.5% (bps=15) using integer math.

## 10. Payment Architecture

### 10.1 MOPAY
*   **Role**: External gateway conduit.
*   **Status**: `PENDING` until external confirmation.

### 10.2 Providers
*   **Type**: Metadata strings.
*   **Values**: `MPESA`, `ECOCASH`, `BANK`.

### 10.3 Swift Wallet
*   **Role**: Internal P2P/Escrow.
*   **Status**: `CONFIRMED` immediately on atomic debit.

### 10.4 Ledger
*   Double-entry consistency enforced for every transaction.

### 10.5 Idempotency
*   `LOCKED`: Mandatory for creation; identified gaps in status updates.

### 10.6 Financial Authority & Safety
The treasury and double-entry ledger are the authoritative financial records.
*   **Money Model**: `MoneyAmount` object with integer minor-unit representation (LSL).
*   **Authority**: Clients NEVER modify balances or ledger.
*   **Payment Safety**: Order creation != payment success. Payment verification is server-authoritative.
*   **Inventory Safety**: Reservation must be reversible. Final commitment occurs only after authoritative payment verification.
*   **Separation**: Order Authority (Workflow) is distinct from Financial Authority (Ledger).

## 11. Social / Feed Architecture
*   **Feed**: Multi-type (Post, Reel, Shop, Listing).
*   **Ranking**: Weighted factors (freshness, engagement, social).
*   **Status**: `IMPLEMENTED` (Authoritative triggers).

## 12. Profile Architecture
*   **Separation**: Private `users` vs Public `profiles`.
*   **Privacy**: Users cannot read other users' private data.
*   **UX Concept**: `Listings | Posts | Shops | My Orders`.
*   **My Orders**: Relationship-aware view of the canonical Order system.
    *   **As Buyer**: Orders I placed.
    *   **As Seller/Author**: Orders received for my Listings.
    *   **As Service/Delivery Provider**: Assigned Bookings/Requests.
    *   **As Admin**: Canonical Transaction View.
*   **Tracking View**: Capable of displaying physical deliveries, service fulfillment, appointments, and form/application workflows based on `FulfillmentType`.

## 13. Search & Discovery
*   **Universal Search**: `IMPLEMENTATION STATUS: INCOMPLETE`.
*   **Requirement**: Search by title, tags, and categories.

## 14. Advertising & Monetization
*   **Revenue**: 1.5% fee, Ads, Tiers.
*   **Tier Limits**: BASIC (3 shops, 10 listings/shop), PREMIUM (5 shops, 50 listings total), ELITE (inf/inf).

## 15. Admin / Governance
*   City treasury and oversight.
*   Admin-only functions (Withdrawal approval, account suspension).

## 16. Security & Moderation
*   `firestore.rules` enforces P0 privacy and counter protection.
*   E2E Messaging: `SCHEMA-READY` (Encryption inactive).

## 17. Logistics & Fulfillment
*   **Status**: `NOT IMPLEMENTED / BLUEPRINT ONLY`.

### 17.1 Architectural Model
`LISTING → PURCHASE → ORDER → FULFILLMENT`

### 17.2 Fulfillment Layer
Fulfillment is the operational layer associated with an Order:
```
ORDER
  ↓
FULFILLMENT
     ├── PHYSICAL_DELIVERY
     ├── SERVICE
     ├── APPOINTMENT
     ├── FORM_WORKFLOW
     └── FUTURE_TYPES
```

### 17.3 Tracking Projection
Tracking is a user-facing projection of fulfillment state, not a separate independent system.

### 17.4 Role-Specific Projections
```
                   ORDER
                     │
         ┌───────────┼───────────┐
         ▼           ▼           ▼
       BUYER       SELLER     DELIVERY PROVIDER
         │           │           │
         ▼           ▼           ▼
      Tracking    Fulfillment  Delivery Request
```

### 17.5 Physical Delivery Lifecycle
`PAID → ACCEPTED → PICKUP_PENDING → PICKED_UP → IN_TRANSIT → DELIVERED`

### 17.6 Financial Boundary
`Order → Payment → Ledger` remains authoritative. Fulfillment is operational state and MUST NEVER become a second financial ledger.

### 17.7 GPS & Live Tracking
`STAGE 2`: GPS/Live location tracking is a future capability and not part of the initial fulfillment implementation. Live location is owned by the Geospatial subsystem.

## 18. Swift Maps & Geospatial
*   **Status**: `BLUEPRINT ONLY / NOT IMPLEMENTED`.

### 18.1 Primary Architectural Principle
> **SWIFT BACKEND DATA IS AUTHORITATIVE; THE MAP IS A VISUALIZATION AND INTERACTION LAYER.**

The map must NEVER become the source of truth for commercial, financial, or fulfillment state.

### 18.2 Boundary between Maps and Fulfillment
*   **Fulfillment & Tracking owns**: Operational lifecycle, assignment, execution status, and fulfillment events.
*   **Swift Maps & Geospatial owns**: Geographic visualization, pin selection, route rendering, map markers, and provider abstraction.

Maps **consumes** authoritative fulfillment information but does not define the fulfillment lifecycle.

### 18.3 Canonical Swift Map Language
Conceptual UI primitives:
| Primitive | Meaning            |
| --------- | ------------------ |
| 🏪        | Shop / Business    |
| 🛍️       | Product            |
| 🛠️       | Service            |
| 📋        | Form / Application |
| 🚚        | Delivery           |
| 📍        | Drop Your Pin      |
| 👤        | Seller             |
| 🚚/👤     | Delivery Provider  |
| 👤        | Buyer              |

### 18.4 Drop Your Pin
Defined as a canonical Swift geospatial interaction primitive. Reusable for:
*   Pickup/Fulfillment locations.
*   Delivery destinations.
*   Service/Appointment locations.
*   Custom meeting points.

### 18.5 Delivery Tracking Architecture
Conceptual relationship:
`Order → Fulfillment → Delivery → Location/Tracking Data → Swift Maps Visualization`

Distinction:
*   **Static Data**: Seller fulfillment location, Buyer destination.
*   **Dynamic Data**: Delivery-provider live/last-known location, movement updates.

### 18.6 Three-View / One-Truth Model
Shop, Delivery, and Admin consume the same canonical logistics/geospatial truth. These are different views over shared authoritative state.

## 19. Geography & Operating Regions
*   **Principle**: The architecture must remain region-independent and must not hard-code specific geographies into the domain model.

### 19.1 Maseru / Lesotho Location Model
Maseru is the initial first-class Swift operating geography. The location model supports Maseru-style addressing:
*   **Components**: district, areaName, landmarkOrDescription, coordinates (GeoPoint).
*   **Landmarks**: Human-readable descriptions (e.g., "near main stop", "opposite police station") are first-class information.
*   **GPS**: Coordinates complement but do not replace human-readable landmark addressing.
*   **Strategy**: Maseru-first defaults informed by local commerce patterns and future validated demographic data.

## 20. Map Provider & Performance

### 20.1 Provider Abstraction
The subsystem requires a provider-independent architecture to prevent vendor lock-in.
`SWIFT MAPS & GEOSPATIAL → Provider Abstraction → (Provider A | Provider B | Future)`

### 20.2 Future Contract Requirements
*   **Privacy**: Location permissions, user consent, role-based visibility, retention/deletion policies.
*   **Performance**: GPS update throttling, battery impact, offline caching, degraded connectivity behavior.
*   **Security**: Authorization for location exposure.

## 21. Web Application
*   `PLANNED`: Guest checkout, SEO listings.

## 22. Firebase Contract Matrix
| Service | Environment | Status |
| :--- | :--- | :--- |
| Auth | swift-dev-3d3ae | `WORKING` |
| Firestore | swift-dev-3d3ae | `WORKING` |
| Functions | swift-dev-3d3ae | `WORKING` |

## 23. Firestore Contract Matrix
Refer to: [SWIFT_FIRESTORE_CONTRACT_MATRIX.md](SWIFT_FIRESTORE_CONTRACT_MATRIX.md)
*   **Listing → Order Relationship**: `orders/{orderId}` must contain `sourceListingId` and a `ListingSnapshot`.

## 24. Cloud Function Contract Matrix
Refer to: [SWIFT_CLOUD_FUNCTION_CONTRACT_MATRIX.md](SWIFT_CLOUD_FUNCTION_CONTRACT_MATRIX.md)
*   **Listing → Order Flow**: Functions must respect the Listing-to-Order instantiated workflow and snapshotting requirements.

## 25. Repository / Domain Contract Matrix
Refer to: [SWIFT_SHOP_CANONICAL_CONTRACT_MATRIX.md](SWIFT_SHOP_CANONICAL_CONTRACT_MATRIX.md)
*   **Listing & Order Domains**: Models must support polymorphic payloads and role-based participants.

## 26. Application Navigation Model
*   `Screen` (Sealed Class) in `:core:ui`.
*   Route-based navigation.

## 27. Current Implementation Status
*   **Core**: `COMPILED`.
*   **Data**: `PARTIAL` (Firebase repo active; local Room pass-through).
*   **Domain**: `COMPLETE`.
*   **Feature**: `WORKING` (UI/VM wired).
*   **Checkout**: `RECONCILED` (Provider metadata).
*   **Profile**: `RECONCILED` (Authoritative counters).
*   **Marketplace Architecture**: `ARCHITECTURALLY DEFINED` (Listing-to-Order revision locked). `NOT IMPLEMENTED`.

### 27.1 Maps & Geospatial Status
```text
STATUS:
BLUEPRINT ONLY
NOT IMPLEMENTED

Maps SDK:
NOT SELECTED (OSM used in experimental UI)

Map Provider:
NOT SELECTED

Geospatial Contract:
NOT YET LOCKED

Implementation:
NOT AUTHORIZED

Firebase Changes:
NONE AUTHORIZED

Android Changes:
NONE AUTHORIZED
```

## 28. Physical Device Runtime Test Results
**RUNTIME OBSERVATION — USER DEVICE TEST**
Device: `2ENBB23C05003307` (Android 12)
*   `PASS`: App Launch, Auth Screen, Home Nav, Profile Nav, Settings, Feed Nav, Toggles, Logout, Listing Detail (images/title/price/qty).
*   `FAIL`: Search results, Add to Cart (UI), Buy Now (UI), Share/Bookmark, Create Listing (Shop selector missing, publish error), Create Post/Reel (Incomplete).

## 29. Known Bugs
*   **P0**: `cancelOrder` fails to restore `stockQuantity` to Listings.
*   **P1**: Missing idempotency on `cancelOrder` and `confirmMopayPayment`.
*   **P1**: `recentListings` and `recentPosts` feeds are hardcoded to empty.

## 30. Architectural Gaps
*   Search indexing service.
*   Media transcoding pipeline.
*   Driver-location background service.
*   Geospatial provider abstraction layer.

## 31. Architectural Unknowns
*   Mopay API v2 compatibility.
*   Escrow wait time rules.
*   Maseru high-quality street coverage availability.

## 32. Decisions & Contract Locks
*   **MONEY-001**: MoneyAmount object only. `LOCKED`.
*   **PAYMENT-001**: MOPAY gateway + provider metadata. `LOCKED`.
*   **ACCOUNT-001**: UserAccountStatus enum canonical. `LOCKED`.
*   **MAPS-001**: SWIFT MAPS & GEOSPATIAL is a separate canonical subsystem from FULFILLMENT & TRACKING. `LOCKED`.
*   **MAPS-002**: Swift backend/domain data is authoritative; maps are visualization and interaction layers. `LOCKED`.
*   **MAPS-003**: Maseru is the initial operating geography; geography must remain extensible. `LOCKED`.
*   **MAPS-004**: Drop Your Pin is a canonical Swift geospatial primitive. `LOCKED`.
*   **MAPS-005**: Delivery pricing remains a Commerce concern; maps/routes do not determine pricing. `LOCKED`.
*   **ORDER-001**: Listing-to-Order Architecture. Listing is the source definition; Order is the instantiated response. `LOCKED`.
*   **ORDER-002**: ListingType and OrderType are separate concepts. `LOCKED`.
*   **ORDER-003**: Orders use role-based participants rather than assuming only Buyer/Seller. `LOCKED`.
*   **ORDER-004**: A single canonical Order supports multiple role-aware perspectives. `LOCKED`.
*   **ORDER-005**: Orders preserve a transactional Listing Snapshot. `LOCKED`.
*   **ORDER-006**: Admin uses the canonical Order model rather than a duplicate Admin Order entity. `LOCKED`.
*   **ORDER-007**: Order payloads are controlled and type-specific. `LOCKED`.
*   **ORDER-008**: Order, Payment, Fulfillment, Inventory, Settlement, and Ledger lifecycles are independent. `LOCKED`.

## 33. Phase History
*   Phase 8B-8D: Integrity Hardening & Tier limits.
*   Phase 8E.1-8E.3: Social फाउंडेशन & Maintenance.
*   Phase 8E.4: Payment Contract Reconciliation.
*   Phase 9: Swift Maps & Geospatial Architecture Blueprint.

## 34. Current Priority Backlog
1.  `P0`: Restore stock on order cancellation.
2.  `P0`: Fix Search Title results.
3.  `P0`: Functional Add to Cart / Buy Now.
4.  `P1`: Listing → Shop link UI.
5.  `P1`: Create Listing Shop selector.

## 35. Admin Completion Roadmap
1.  Financial summary dashboard.
2.  Withdrawal approval workflow.
3.  User account status management.

## 36. Shop Completion Roadmap
1.  Dynamic Forms for Listings.
2.  Appointment calendar integration.
3.  Search optimization.

## 37. Web Roadmap
1.  Public Listing/Shop pages.
2.  Search engine indexing.

## 38. Future Architecture
*   Cross-border currency conversion.
*   Logistics optimization engine & Stage 2 GPS/Live tracking.

## 39. Documentation Governance
`SWIFT_MASTER_PROJECT_BLUEPRINT.md` is the single source of truth.

## 40. Architectural Conflict Register

| ID | Topic | Source A | Source B | Conflict | Authority | Resolution |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| CF-001 | Money Type | Admin (Long) | Shop (Object) | Primitive vs Object | DECISION D-001 | `MoneyAmount` Object |
| CF-002 | Account Status | Shop (Bool) | Admin (Enum) | Boolean vs 7-state Enum | DECISION D-002 | `UserAccountStatus` Enum |
| CF-003 | Location | Admin (Double) | Shop (GeoPoint) | Primitive vs DTO | FORENSIC | `GeoPoint` Object |
| CF-004 | Listing vs Order | Forensic | Domain | Definition vs Instantiation | ORDER-001 | Listing is Source; Order is Response |
| CF-005 | Type Conflation | Forensic | Domain | ListingType vs OrderType | ORDER-002 | Separate Concepts |
| CF-006 | Participant Model | Forensic | Domain | Buyer/Seller vs Roles | ORDER-003 | Role-Based Participants |
| CF-007 | Order Collection | Forensic | Domain | Multiple vs Canonical | ARCH | Canonical `orders/{id}` |
| CF-008 | Payload Structure | Forensic | Domain | Map vs Polymorphism | ORDER-007 | Controlled Payloads |
| CF-009 | Authority | Finance | Engine | Finance vs Order | ARCH | Order Authority coords Finance |
| CF-010 | Listing Mutability | Forensic | Audit | Mutable vs Snapshot | ORDER-005 | Transactional Snapshot |
| CF-011 | Lifecycle | Order | Payment | Collapsed vs Independent | ORDER-008 | Independent States |
| CF-012 | Inventory | Order | Inventory | Implicit vs Explicit | ORDER-008 | Separate Inventory State |

## 41. Source Document Register

| Document | Classification | Authority | Status | Incorporated? | Disposition |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `ARCHITECTURE.md` | Canonical | 1 | CANONICAL | YES | REFERENCE |
| `SWIFT_MASTER_PROJECT_BLUEPRINT.md` | Canonical | 1 | CANONICAL | YES | MASTER |
| `Phase8F_Audit.../SWIFT_SHOP_PHASE8F_CROSS_SYSTEM_REGRESSION_AUDIT.md` | Forensic | 7 | CANONICAL | YES | REFERENCE |
| `SWIFT_ECOSYSTEM_CANONICAL_DECISION_REGISTER.md` | Decision | 3 | CANONICAL | YES | REFERENCE |
| `SALVAGE_NOTES.md` | Forensic | 7 | REFERENCE | YES | REFERENCE |
| `SWIFT_SHOP_CHECKOUT_REPAIR_REPORT.md` | Implementation | 5 | CANONICAL | YES | REFERENCE |
| `SWIFT_SHOP_PHASE8E.3_IMPLEMENTATION_REPORT.md` | Implementation | 4 | REFERENCE | YES | REFERENCE |
| `SWIFT_SHOP_PHASE8E_FINANCIAL_INTEGRITY_FREEZE.md` | Contract | 2 | LOCKED | YES | REFERENCE |
| `SWIFT_FIRESTORE_CONTRACT_MATRIX.md` | Contract | 2 | CANONICAL | YES | REFERENCE |
| `SWIFT_CLOUD_FUNCTION_CONTRACT_MATRIX.md` | Contract | 2 | CANONICAL | YES | REFERENCE |
| `SWIFT_SHOP_CANONICAL_CONTRACT_MATRIX.md` | Contract | 2 | CANONICAL | YES | REFERENCE |
| `SWIFT_SHOP_CANONICAL_MODEL_MATRIX.md` | Contract | 2 | CANONICAL | YES | REFERENCE |

## 42. Implementation Migration Principle
The migration to the revised Listing-to-Order architecture must follow a server-authoritative, financially safe, and backward-compatible path:
1.  **Architectural Contract Revision**: (Complete) Establish the contract in the Blueprint.
2.  **Domain Contract Evolution**: Update Kotlin domain models and sealed classes.
3.  **DTO / Mapper Compatibility**: Ensure existing Firestore data maps correctly to new domain models.
4.  **Use Case Migration**: Update logic for order creation, listing snapshots, and role-based access.
5.  **UI Migration**: Update Shop and Admin interfaces for polymorphic order views.
6.  **Backend Alignment**: Reconcile Cloud Functions with the snapshot and authority models.
7.  **Integration Verification**: Automated and manual testing of the end-to-end flow.
8.  **Physical Device Testing**: Validation on actual hardware in target geographies.

## 43. Change Log
*   2026-08-28: Initial consolidation into Master Project Blueprint.
*   2026-08-28: Added Architectural Conflict Register.
*   2026-09-01: Introduced Swift Fulfillment & Tracking Subsystem architecture.
*   2026-09-02: Formalized Swift Maps & Geospatial subsystem. Added ADRs (MAPS-001 to MAPS-005). Established Maseru as initial geography.
*   2026-09-08: Established Listing-to-Order marketplace architecture. Introduced polymorphic payloads, transactional snapshots, role-based participants, and Maseru landmark addressing. Added ADRs (ORDER-001 to ORDER-008). Added Conflict Register entries (CF-004 to CF-012).
*   2026-09-08: Remedied polymorphic payload read-path gaps, implemented role-based UI perspectives, and restored Cloud Functions build integrity.

