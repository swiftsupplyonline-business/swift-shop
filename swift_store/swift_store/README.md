# Swift Shop Client — Android App

Lesotho's commercial mobile ecosystem. Built with Kotlin + Jetpack Compose.

---

## Quick Start

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34
- Firebase project (see Firebase Setup below)
- Google account for Play Services

### Clone & Open
```bash
git clone https://github.com/your-org/SwiftShop.git
cd SwiftShop
```
Open the root folder in Android Studio.

### Environment Setup

Copy `gradle.properties.template` to `gradle.properties` and fill in your values:

```properties
MAPS_API_KEY=your_maps_api_key
MAPBOX_ACCESS_TOKEN=your_mapbox_token
MOPAY_BASE_URL=https://api.mopay.co.ls/v1/
MOPAY_PUBLIC_KEY=your_mopay_public_key
BACKEND_BASE_URL=https://your-backend.run.app/api/v1/
```

**Never commit real values.** Use CI environment variables or Android Studio's local.properties.

### Firebase Setup

1. Create a Firebase project at https://console.firebase.google.com
2. Add an Android app with package `com.swiftshop.client`
3. Download `google-services.json` and place it in `app/`
4. Enable: Authentication (Email/Password, Phone), Firestore, Storage, Cloud Messaging
5. Deploy Firestore rules: `firebase deploy --only firestore:rules`
6. Deploy Firestore indexes: `firebase deploy --only firestore:indexes`

### Build

```bash
# Debug build (dev flavor)
./gradlew :app:assembleDevDebug

# Release build (production flavor)
./gradlew :app:assembleProductionRelease

# All variants
./gradlew assemble
```

### Run Tests

```bash
# Unit tests
./gradlew test

# Specific module
./gradlew :app:testDevDebugUnitTest

# Android instrumented tests (requires connected device or emulator)
./gradlew :app:connectedDevDebugAndroidTest
```

### Install on Device

```bash
./gradlew :app:installDevDebug
```

---

## Product Flavors

| Flavor      | App ID suffix | Logging | Payment gateway |
|-------------|--------------|---------|-----------------|
| dev         | .dev         | ON      | Mock            |
| staging     | .staging     | ON      | Mock            |
| production  | (none)       | OFF     | Mopay (real)    |

---

## Module Structure

```
app/                        Entry point, DI wiring, navigation, services
core/
  common/                   Shared utilities, extension functions
  model/                    All domain data classes (User, Listing, Order, MoneyAmount…)
  network/                  Retrofit, OkHttp, PaymentGateway abstraction
  database/                 Room (offline cache)
  datastore/                Preferences DataStore
  ui/                       Theme, colors, typography, shared components
  security/                 Biometric, encrypted storage
  media/                    Image/video compression, upload engine
data/
  firebase/                 Firestore repository implementations
  local/                    Room-backed local repositories
  remote/                   Backend API repositories
domain/
  auth/                     Auth use cases and AuthRepository interface
  commerce/                 Shop, listing, cart, order use cases
  wallet/                   Wallet, ledger, transfer use cases
  feed/                     Feed ranking engine and paging use cases
  profile/                  Profile, follow, achievements
  messaging/                Conversation and message use cases
  advertising/              Ad campaign use cases
  delivery/                 Route tracking use cases
feature/
  auth/                     Login / Register screens
  home/                     Feed (Shop | Posts | Reels tabs)
  search/                   Universal search
  shop/                     Listing detail, shop detail, create listing
  posts/                    Post detail, create post
  reels/                    Create reel
  checkout/                 Cart → Address → Payment → Confirm
  orders/                   Order list and detail
  profile/                  User profile + wallet card
  wallet/                   Full wallet + transaction history
  messaging/                Conversation list + chat
  delivery/                 Live delivery tracking (OSM map)
  advertising/              Ad campaign creation
  settings/                 App settings
```

---

## Architecture

Clean Architecture with Hilt DI.

```
UI (Compose)
  ↓
ViewModel (StateFlow, sealed UiState)
  ↓
Domain Use Cases (pure Kotlin, no Android deps)
  ↓
Repository Interfaces
  ↓
Data Sources (Firestore | Room | Retrofit)
```

See `ARCHITECTURE.md` for full diagram.

---

## Known Limitations (Phase 1)

| Feature | Status | Notes |
|---------|--------|-------|
| Mopay adapter | Mock only | Wire `MopayAdapter` in `NetworkModule` when Mopay credentials available |
| E2E Encryption | NOT implemented | Messaging uses Firestore (TLS in transit, not E2EE). Do not claim E2EE. |
| Phone auth | Partially wired | Requires Activity context for `PhoneAuthProvider` |
| Media pipeline | Implemented | `CreatePostScreen` / `CreateReelScreen` are functional; wire additional compression if needed |
| Search ranking | Mock delay | `SearchViewModel` returns empty after 500ms; wire Firestore full-text (Algolia/Typesense) |
| Mapbox | Not wired | OSM (osmdroid) is wired; Mapbox requires license token |
| Push notifications | FCM wired | Token sync WorkManager job needs implementing |
| Biometric auth | Setting toggle only | Wire `BiometricPrompt` in security module |

---

## Security Notes

- No payment secrets exist in the client.
- All financial operations (order creation, wallet credits/debits, fee calculations) are server-authoritative.
- Firestore rules prevent clients from writing to wallets, ledger entries, or orders directly.
- Secrets are excluded from version control via `gradle.properties` (gitignored).

See `SECURITY.md` for full security posture.
