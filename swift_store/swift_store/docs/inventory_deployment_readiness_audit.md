# Production Inventory Deployment Readiness Audit

**Project:** swift-dev-3d3ae
**Date:** 2026-09-08
**Status:** COMPLETE (Checkpoint Baseline)

## 1. Objective
Establish a safe deployment boundary for transitioning the production environment from the legacy `stockQuantity` inventory model to the canonical reservation architecture.

## 2. Forensic Findings

### Production State
- **Current Revision:** `createorder-00011` (Deployed 2026-09-06)
- **Inventory Model:** Legacy `stockQuantity` direct-decrement.
- **Behavior:** Immediately decrements `stockQuantity` at order creation. Skips validation if `stockQuantity` is missing/null.

### Local State
- **Inventory Model:** Canonical Reservation-Hold.
- **Fields:** `totalQuantity`, `reservedQuantity`, `availableQuantity`.
- **Behavior:** Creates `ACTIVE` reservations with 15-minute TTL holds. Rejects legacy listings during checkout (Strict Guard).

### Infrastructure Gaps
- **Cloud Scheduler API:** Currently **DISABLED** in `swift-dev-3d3ae`.
- **Reaper:** `cleanupExpiredReservations` is NOT yet deployed.

### Data Inconsistency
- **Legacy Listings:** Five listings identified as lacking canonical inventory fields.
- **Outstanding Orders:** Four `PENDING` orders currently hold legacy inventory.

## 3. Deployment Requirements

### Minimum Atomic Deployment Set
The following functions must be deployed together to ensure state-machine compatibility:
- `createOrder`
- `verifyMopayPayment`
- `cancelOrder`
- `cleanupExpiredReservations` (Requires Scheduler API)
- `createListing` / `updateListing`

### Safe Sequence
1. **Enable Infrastructure:** Enable Cloud Scheduler API.
2. **Coordinated Deployment:** Deploy the atomic function set.
3. **Data Migration:** Initialize `totalQuantity` and `reservedQuantity` for identified legacy listings.
4. **Verification:** Confirm reaper execution and end-to-end reservation lifecycle.

## 4. Safety Verification
- **Production writes:** NONE
- **Deployments:** NONE
- **Data migration:** NONE
- **Scheduler changes:** NONE
- **Auth changes:** NONE
- **Financial changes:** NONE

---
*This document serves as a baseline checkpoint before implementation begins.*
