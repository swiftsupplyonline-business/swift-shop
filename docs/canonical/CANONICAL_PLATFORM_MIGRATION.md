# Canonical platform migration: what was done, in order

Base: canonical-platform-tree-2026-09-29 @ 33d711b. Everything below is on branches; nothing is merged to `main`.
Chain (each branch builds on the previous): block-0-canonical-baseline > block-1-listing-engine > block-2-purchase-first >
block-4-fulfillment > block-5-android-delivery > block-6-web-links > block-1b-firestore-rules > block-7-update-shop >
block-8-rules-money > block-9-order-money > go-live-hardening-batch-a (032bda0) > go-live-remediation-c (this branch).

NOTE: Blocks 0-7 were also merged into `canonical-platform-integration-2026-09-29` (merge commit 44d13cc). That is a DIFFERENT
branch name from `canonical-platform-tree-2026-09-29`, which still points at 33d711b.

Details per block: BLOCK_1_LISTING_AUDIT.md, BLOCK_2_PURCHASE_AUDIT.md, BLOCK_4_FULFILLMENT_AUDIT.md, BLOCK_5_ANDROID_DELIVERY.md,
BLOCK_6_WEB_LINKS.md, BLOCK_8_RULES_AND_MONEY.md, and GO_LIVE_AUDIT_RECONCILIATION.md for the current status.
