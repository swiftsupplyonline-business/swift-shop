import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { MopayClient, MOPAY_API_KEY } from "./mopay";

/**
 * Calculates authoritative order fees server-side.
 *
 * Contract:
 * - Request: { items: OrderItem[], deliveryAddress: DeliveryAddress }
 * - Response: OrderSummary
 */
export const calculateOrderFees = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { items, deliveryListingId } = request.data;
    if (!items || !Array.isArray(items)) throw new HttpsError("invalid-argument", "Missing items");
    if (!deliveryListingId) throw new HttpsError("invalid-argument", "deliveryListingId is required");

    const db = admin.firestore();

    let deliveryFee = 0;
    if (deliveryListingId !== "none") {
        const deliveryListingDoc = await db.collection("listings").doc(deliveryListingId).get();
        if (!deliveryListingDoc.exists) {
            throw new HttpsError("not-found", `Delivery listing ${deliveryListingId} not found`);
        }
        const deliveryListing = deliveryListingDoc.data()!;
        if (deliveryListing.listingType !== "DELIVER" && deliveryListing.listingType !== "DELIVERY_SERVICE") {
            throw new HttpsError("invalid-argument", "The provided listing is not a delivery listing");
        }
        if (!deliveryListing.isAvailable) {
            throw new HttpsError("failed-precondition", "The selected delivery listing is no longer available");
        }
        deliveryFee = deliveryListing.priceMinorUnits || 0;
    }


    // ── Item subtotal (authoritative price from each listing) ──────────────────
    let subtotal = 0;
    for (const item of items) {
        const listingDoc = await db.collection("listings").doc(item.listingId).get();
        if (!listingDoc.exists) throw new HttpsError("not-found", `Listing ${item.listingId} not found`);
        const listing = listingDoc.data()!;
        subtotal += (listing.priceMinorUnits || 0) * (item.quantity || 1);
    }

    // ── Platform fee: 1.5% using integer arithmetic ────────────────────────────
    const platformFee = Math.floor((subtotal * 15) / 1000);
    const total = subtotal + deliveryFee + platformFee;

    return {
        subtotalMinorUnits: subtotal,
        deliveryFeeMinorUnits: deliveryFee,
        platformFeeMinorUnits: platformFee,
        totalMinorUnits: total,
        currency: "LSL",
        deliveryListingId,
        orderType: "PRODUCT_PURCHASE" // Default for multi-item product checkout
    };
});

/**
 * Mapping from ListingType to OrderType.
 */
const MAP_LISTING_TO_ORDER_TYPE: Record<string, string> = {
    "PRODUCT": "PRODUCT_PURCHASE",
    "PHYSICAL_ITEM": "PRODUCT_PURCHASE",
    "BUY": "PRODUCT_PURCHASE",
    "SERVICE": "SERVICE_BOOKING",
    "BOOKABLE_SERVICE": "SERVICE_BOOKING",
    "SET_APPOINTMENT": "SERVICE_BOOKING",
    "PREPARED_FOOD": "FOOD_ORDER",
    "BULK_SUPPLY": "BULK_PURCHASE",
    "DELIVER": "DELIVERY_REQUEST",
    "DELIVERY_SERVICE": "DELIVERY_REQUEST"
};

/**
 * Captures a transactional snapshot of a Listing.
 */
function captureListingSnapshot(listing: any): any {
    return {
        listingId: listing.id,
        shopId: listing.shopId,
        sellerId: listing.sellerId,
        sellerName: listing.sellerName || "",
        title: listing.title,
        description: listing.description,
        priceMinorUnits: listing.priceMinorUnits,
        currency: listing.priceCurrency || "LSL",
        listingType: listing.listingType,
        category: listing.category || "",
        variantId: null,
        fulfillmentOptions: listing.fulfillmentOptions || [],
        snapshotAt: Date.now()
    };
}

/**
 * Creates a server-authoritative order.
 *
 * Logic:
 * 1. Validate auth and inputs.
 * 2. Start Firestore transaction.
 * 3. Check idempotency.
 * 4. Verify each listing (existence, availability, price, stock).
 * 5. Calculate authoritative totals.
 * 6. Decrement stock quantity.
 * 7. Create PENDING order document.
 * 8. Record idempotency.
 * 9. Return orderId.
 */
export const createOrder = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { items, deliveryAddress, deliveryListingId, paymentMethod, provider, idempotencyKey, recipientUid } = request.data;
    if (!items || !Array.isArray(items) || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing items or idempotencyKey");
    }

    if (!deliveryListingId) {
        throw new HttpsError("invalid-argument", "deliveryListingId is required");
    }

    const db = admin.firestore();
    let orderId = "";
    let finalTotal = 0;

    try {
        let deliveryFeeFromListing = 0;
        let deliveryListingSnapshot = null;

        if (deliveryListingId !== "none") {
            const deliveryListingDoc = await db.collection("listings").doc(deliveryListingId).get();
            if (!deliveryListingDoc.exists) {
                throw new HttpsError("not-found", `Delivery listing ${deliveryListingId} not found`);
            }
            const deliveryListingData = deliveryListingDoc.data()!;
            if (deliveryListingData.listingType !== "DELIVER" && deliveryListingData.listingType !== "DELIVERY_SERVICE") {
                throw new HttpsError("invalid-argument", "The provided listing is not a delivery listing");
            }
            if (!deliveryListingData.isAvailable) {
                throw new HttpsError("failed-precondition", "The selected delivery option is no longer available");
            }
            deliveryFeeFromListing = deliveryListingData.priceMinorUnits || 0;

            deliveryListingSnapshot = {
                listingId: deliveryListingId,
                providerId: deliveryListingData.sellerId || "",
                providerName: deliveryListingData.providerName || "",
                title: deliveryListingData.title || "",
                priceMinorUnits: deliveryFeeFromListing,
                currency: deliveryListingData.priceCurrency || "LSL",
                estimatedMinutes: deliveryListingData.estimatedMinutes || 0
            };
        }

        // STEP 1: Atomic Inventory Reservation & Order Record Creation
        const result = await db.runTransaction(async (transaction) => {
            // 1. Idempotency Check
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                console.log(`Idempotent request for key ${idempotencyKey}. Returning existing orderId.`);
                return { orderId: idempotencyDoc.data()?.orderId, total: 0, items: [], alreadyExists: true };
            }

            // 1a. Pre-read Listings to determine OrderType and validate consistency
            const listingRefs = items.map(item => db.collection("listings").doc(item.listingId));
            const listingSnaps = [];
            for (const ref of listingRefs) {
                const snap = await transaction.get(ref);
                if (!snap.exists) throw new Error(`Listing ${ref.id} not found`);
                listingSnaps.push(snap);
            }

            const listingDataList = listingSnaps.map(snap => snap.data()!);
            const types = listingDataList.map(l => MAP_LISTING_TO_ORDER_TYPE[l.listingType] || "PRODUCT_PURCHASE");
            const uniqueTypes = [...new Set(types)];

            if (uniqueTypes.length > 1) {
                throw new Error(`Mixed order types detected: ${uniqueTypes.join(", ")}. Orders must contain listings of compatible types only.`);
            }
            const orderType = uniqueTypes[0];

            // 1b. Reject incompatible multi-item types (e.g. Services can't be in a cart with other things)
            if (orderType === "SERVICE_BOOKING" && items.length > 1) {
                throw new Error("Service bookings must be initiated individually.");
            }

            let subtotal = 0;
            const newOrderId = db.collection("orders").doc().id;
            const validatedItems = [];
            let shopId = "";
            let sellerId = "";

            // 1c. Wallet Read
            const isWalletPayment = paymentMethod === "SWIFT_WALLET";
            const walletRef = db.collection("wallets").doc(auth.uid);
            const walletDoc = isWalletPayment ? await transaction.get(walletRef) : null;
            if (isWalletPayment && (!walletDoc || !walletDoc.exists)) {
                throw new Error("Wallet not found. Deposit funds before checking out with Swift Wallet.");
            }
            const walletAvailableBefore = isWalletPayment ? (walletDoc!.data()!.availableBalanceMinorUnits || 0) : 0;
            const walletCurrency = isWalletPayment ? (walletDoc!.data()!.currency || "LSL") : "LSL";

            // 2. Validate Items, Shop Consistency, and Inventory
            for (let i = 0; i < items.length; i++) {
                const item = items[i];
                const listing = listingDataList[i];

                if (!listing.isAvailable) throw new Error(`Listing ${item.listingId} is not available`);

                if (!shopId) {
                    shopId = listing.shopId;
                    sellerId = listing.sellerId;
                } else if (listing.shopId !== shopId) {
                    throw new Error("Multi-shop orders are not supported in this version.");
                }

                const requestedQty = item.quantity || 1;

                // CANONICAL INVENTORY VALIDATION
                if (listing.totalQuantity === undefined || listing.reservedQuantity === undefined) {
                    throw new HttpsError("failed-precondition", `Listing ${item.listingId} requires inventory reconciliation.`);
                }

                const available = listing.totalQuantity - listing.reservedQuantity;
                if (available < requestedQty) {
                    throw new Error(`Insufficient stock for ${listing.title}. Requested: ${requestedQty}, Available: ${available}`);
                }

                subtotal += listing.priceMinorUnits * requestedQty;

                validatedItems.push({
                    listingId: item.listingId,
                    title: listing.title,
                    quantity: requestedQty,
                    unitPriceMinorUnits: listing.priceMinorUnits,
                    unitPriceCurrency: listing.priceCurrency || "LSL"
                });
            }

            // 3. PERFORM ALL WRITES (Atomic Reservation)
            const now = admin.firestore.Timestamp.now();
            const expiresAt = admin.firestore.Timestamp.fromMillis(now.toMillis() + 15 * 60 * 1000);

            for (let i = 0; i < listingSnaps.length; i++) {
                const snap = listingSnaps[i];
                const requestedQty = items[i].quantity || 1;
                const listing = snap.data()!;

                const reservationId = db.collection("reservations").doc().id;
                transaction.set(db.collection("reservations").doc(reservationId), {
                    id: reservationId,
                    orderId: newOrderId,
                    buyerId: auth.uid,
                    sellerId: listing.sellerId,
                    shopId: listing.shopId,
                    listingId: snap.id,
                    quantity: requestedQty,
                    status: "ACTIVE",
                    createdAt: now,
                    expiresAt: expiresAt
                });

                const newReserved = listing.reservedQuantity + requestedQty;
                transaction.update(snap.ref, {
                    reservedQuantity: newReserved,
                    availableQuantity: listing.totalQuantity - newReserved,
                    stockQuantity: listing.totalQuantity - newReserved, // Compatibility mirror
                    updatedAt: now
                });
            }

            // Delivery fee calculations
            const deliveryFee = deliveryFeeFromListing;
            const platformFee = Math.floor((subtotal * 15) / 1000);
            const total = subtotal + deliveryFee + platformFee;

            if (isWalletPayment) {
                if (walletCurrency !== "LSL") throw new Error(`Wallet currency mismatch: ${walletCurrency}.`);
                if (walletAvailableBefore < total) throw new Error("Insufficient wallet balance.");
            }

            const orderStatus = isWalletPayment ? "CONFIRMED" : "RESERVED";

            const participants: Record<string, string> = {
                "REQUESTER": auth.uid,
                "LISTING_AUTHOR": sellerId
            };
            if (orderType === "PRODUCT_PURCHASE" || orderType === "FOOD_ORDER" || orderType === "BULK_PURCHASE") {
                participants["SELLER"] = sellerId;
            } else if (orderType === "SERVICE_BOOKING") {
                participants["SERVICE_PROVIDER"] = sellerId;
            } else if (orderType === "DELIVERY_REQUEST") {
                participants["DELIVERY_PROVIDER"] = sellerId;
            }
            if (recipientUid) participants["RECIPIENT"] = recipientUid;

            const shopDoc = await transaction.get(db.collection("shops").doc(shopId));
            const shopData = shopDoc.exists ? shopDoc.data() : null;
            const originLocationSnapshot = shopData ? {
                lat: shopData.locationLat || 0,
                lng: shopData.locationLng || 0,
                addressSnapshot: shopData.locationAddress || ""
            } : null;

            // ── Payload Allowlisting ──────────────────────────────────────
            const clientPayload = request.data.payload || {};
            let payload: any = null;

            switch (orderType) {
                case "PRODUCT_PURCHASE":
                    payload = {
                        quantity: validatedItems.reduce((acc, i) => acc + i.quantity, 0),
                        unitPriceMinorUnits: validatedItems[0]?.unitPriceMinorUnits || 0,
                        buyerNotes: clientPayload.buyerNotes || ""
                    };
                    break;
                case "FOOD_ORDER":
                    payload = {
                        items: validatedItems.map(i => ({ id: i.listingId, title: i.title, quantity: i.quantity, addOns: clientPayload.items?.find((cp: any) => cp.id === i.listingId)?.addOns || [] })),
                        preparationNotes: clientPayload.preparationNotes || ""
                    };
                    break;
                case "SERVICE_BOOKING":
                    payload = {
                        serviceId: validatedItems[0].listingId,
                        requestedDate: clientPayload.requestedDate || "",
                        requestedTime: clientPayload.requestedTime || "",
                        durationMinutes: listingDataList[0].durationMinutes || 0,
                        locationType: clientPayload.locationType || "ON_SITE"
                    };
                    break;
                case "BULK_PURCHASE":
                    payload = {
                        quantity: validatedItems.reduce((acc, i) => acc + i.quantity, 0),
                        unitOfMeasure: listingDataList[0].unitOfMeasure || "unit",
                        pricingTier: clientPayload.pricingTier || null
                    };
                    break;
                case "DELIVERY_REQUEST":
                    payload = {
                        packageDescription: clientPayload.packageDescription || "",
                        recipientName: clientPayload.recipientName || "",
                        recipientPhone: clientPayload.recipientPhone || "",
                        instructions: clientPayload.instructions || ""
                    };
                    break;
            }

            const orderDoc: Record<string, unknown> = {
                id: newOrderId,
                buyerId: auth.uid,
                sellerId: sellerId,
                shopId: shopId,
                type: orderType,
                sourceListingId: validatedItems[0]?.listingId || "",
                listingSnapshot: captureListingSnapshot(listingDataList[0]),
                participants: participants,
                items: validatedItems,
                payload: payload,
                subtotalMinorUnits: subtotal,
                deliveryFeeMinorUnits: deliveryFee,
                platformFeeMinorUnits: platformFee,
                totalMinorUnits: total,
                currency: "LSL",
                status: orderStatus,
                paymentStatus: isWalletPayment ? "PAID" : "PENDING",
                fulfillmentStatus: "PENDING",
                settlementStatus: isWalletPayment ? "ESCROW_HOLD" : "PENDING",
                inventoryStatus: "RESERVED",
                deliveryAddress: deliveryAddress || {},
                selectedDeliveryListingId: deliveryListingId,
                deliveryListingSnapshot: deliveryListingSnapshot,
                originLocationSnapshot: originLocationSnapshot,
                destinationLocationSnapshot: deliveryAddress ? {
                    lat: deliveryAddress.lat || 0,
                    lng: deliveryAddress.lng || 0,
                    addressSnapshot: deliveryAddress.label || ""
                } : null,
                paymentMethod: paymentMethod || "MOPAY",
                provider: provider || null,
                idempotencyKey: idempotencyKey,
                reservationExpiresAt: expiresAt,
                createdAt: now,
                updatedAt: now
            };

            transaction.set(db.collection("orders").doc(newOrderId), orderDoc);
            transaction.set(idempotencyRef, {
                orderId: newOrderId,
                userId: auth.uid,
                createdAt: now
            });

            if (isWalletPayment) {
                const walletTxnId = db.collection("walletTransactions").doc().id;
                const ledgerId = db.collection("ledgerEntries").doc().id;
                const newAvailable = walletAvailableBefore - total;
                transaction.update(walletRef, { availableBalanceMinorUnits: newAvailable, updatedAt: now });
                transaction.set(db.collection("walletTransactions").doc(walletTxnId), {
                    transactionId: walletTxnId, userId: auth.uid, type: "PURCHASE", amountMinorUnits: total, feeMinorUnits: 0,
                    currency: "LSL", status: "COMPLETE", description: `Order ${newOrderId} (Swift Wallet)`, orderId: newOrderId,
                    idempotencyKey, createdAt: now, updatedAt: now
                });
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId, transactionId: walletTxnId, debitAccount: `user_${auth.uid}`, creditAccount: "system_order_escrow",
                    amountMinorUnits: total, currency: "LSL", reference: `ORDER_CONFIRM_WALLET_${newOrderId}`, timestamp: now
                });
            }

            return { orderId: newOrderId, total, alreadyExists: false };
        });

        orderId = result.orderId;
        finalTotal = result.total;

        if (result.alreadyExists) return { orderId };

        // STEP 2: MoPay Initiation (Outside transaction)
        if (paymentMethod === "MOPAY" && orderId) {
            const mopayRequest = {
                amount: (finalTotal / 100).toFixed(2),
                reference: orderId,
                redirectUrl: "swiftshop://checkout/verify",
                description: `Order ${orderId} at Swift Shop`,
                customerEmail: auth.token.email,
                customerName: auth.token.name || auth.uid,
            };

            const mopayResponse = await MopayClient.initiatePaymentSession(mopayRequest);

            if (mopayResponse.success && mopayResponse.sessionId) {
                await db.collection("orders").doc(orderId).update({
                    mopaySessionId: mopayResponse.sessionId,
                    paymentUrl: mopayResponse.paymentUrl,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                return {
                    orderId,
                    paymentUrl: mopayResponse.paymentUrl,
                    mopaySessionId: mopayResponse.sessionId
                };
            } else {
                console.error("MoPay Session Creation Failed:", mopayResponse.message);

                // STEP 3: Compensating Transaction (Release Stock on Gateway Failure)
                await db.runTransaction(async (transaction) => {
                    for (const item of items) {
                        const listingRef = db.collection("listings").doc(item.listingId);
                        const listingDoc = await transaction.get(listingRef);
                        if (listingDoc.exists) {
                            const listing = listingDoc.data()!;
                            const currentTotal = listing.totalQuantity || 0;
                            const currentReserved = listing.reservedQuantity || 0;
                            const newReserved = Math.max(0, currentReserved - (item.quantity || 1));

                            transaction.update(listingRef, {
                                reservedQuantity: newReserved,
                                availableQuantity: currentTotal - newReserved,
                                stockQuantity: currentTotal - newReserved,
                                updatedAt: admin.firestore.FieldValue.serverTimestamp()
                            });
                        }
                    }
                    transaction.update(db.collection("orders").doc(orderId), {
                        status: "FAILED",
                        error: mopayResponse.message || "Failed to initiate payment gateway",
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });
                });

                return {
                    orderId,
                    error: mopayResponse.message || "Failed to initiate payment gateway"
                };
            }
        }
        return { orderId };

    } catch (error: any) {
        console.error("Order creation failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Verifies a MoPay session and completes the order if successful.
 * This is server-authoritative and does NOT trust client-side parameters.
 */
export const verifyMopayPayment = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { sessionId } = request.data;
    if (!sessionId) throw new HttpsError("invalid-argument", "Missing sessionId");

    const db = admin.firestore();

    try {
        // 1. Locate Order by MoPay Session ID
        const orderQuery = await db.collection("orders")
            .where("mopaySessionId", "==", sessionId)
            .where("buyerId", "==", auth.uid)
            .limit(1)
            .get();

        if (orderQuery.empty) throw new Error("Order not found for this session.");

        const orderDoc = orderQuery.docs[0];
        const order = orderDoc.data();

        // 2. State Gating: Only PENDING or RESERVED orders can be verified
        if (order.status !== "PENDING" && order.status !== "RESERVED") {
            console.warn(`Attempted to verify order ${order.id} in state ${order.status}`);
            return { status: order.status, orderId: order.id };
        }

        // 3. Authoritative Gateway Verification
        const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
        if (!mopaySession) throw new Error("Could not verify session with MoPay.");

        // 4. Validation
        if (mopaySession.reference !== order.id) throw new Error("Session reference mismatch.");

        const mopayAmountMinor = Math.round(mopaySession.amount * 100);
        if (mopayAmountMinor !== order.totalMinorUnits) {
            throw new Error(`Amount mismatch. Expected: ${order.totalMinorUnits}, Got: ${mopayAmountMinor}`);
        }

        // 5. Atomic Transition (only if SUCCESS)
        if (mopaySession.transactionStatus === "SUCCESS") {
            const result = await db.runTransaction(async (transaction) => {
                const freshOrderDoc = await transaction.get(orderDoc.ref);
                const freshOrder = freshOrderDoc.data()!;

                if (freshOrder.status === "CONFIRMED") return { status: "SUCCESS" };
                if (freshOrder.status !== "PENDING" && freshOrder.status !== "RESERVED") {
                    return { status: "ERROR", message: `Cannot confirm order in state ${freshOrder.status}` };
                }

                const isService = freshOrder.type === "SERVICE_BOOKING";
                const now = admin.firestore.Timestamp.now();
                const nowMs = now.toMillis();

                // 1. READ ALL NECESSARY DOCUMENTS
                const listingSnaps = [];
                const reservationSnaps = [];

                if (!isService) {
                    const resQuery = await db.collection("reservations").where("orderId", "==", order.id).get();
                    for (const resDoc of resQuery.docs) {
                        const reservation = resDoc.data();
                        const listingRef = db.collection("listings").doc(reservation.listingId);
                        const listingSnap = await transaction.get(listingRef);
                        reservationSnaps.push({ ref: resDoc.ref, data: reservation });
                        listingSnaps.push({ ref: listingRef, snap: listingSnap });
                    }
                }

                // 2. VALIDATION & STATE CHECKS
                if (isService) {
                    const slotId = freshOrder.slotId;
                    if (!slotId) throw new Error("Service order missing slotId");

                    const slotRef = db.collection("availability").doc(freshOrder.shopId).collection("slots").doc(slotId);
                    const slotSnap = await transaction.get(slotRef);

                    if (!slotSnap.exists) throw new Error("Associated slot not found");
                    const slot = slotSnap.data()!;

                    if ((slot.expiresAt || 0) < nowMs) {
                        transaction.update(orderDoc.ref, {
                            status: "CANCELLED",
                            paymentStatus: "SUCCESS",
                            cancelReason: "Reservation expired before payment verification",
                            updatedAt: now
                        });
                        return { status: "RECONCILIATION_REQUIRED", reason: "EXPIRED" };
                    }

                    if (slot.status !== "RESERVED" || slot.orderId !== freshOrder.id) {
                        throw new Error("Slot is no longer reserved for this order");
                    }

                    // WRITE: BOOK SLOT
                    transaction.update(slotRef, { status: "BOOKED", updatedAt: now });

                    const appointmentRef = db.collection("appointments").doc(freshOrder.id);
                    transaction.set(appointmentRef, {
                        id: freshOrder.id, orderId: freshOrder.id, buyerId: freshOrder.buyerId, sellerId: freshOrder.sellerId,
                        shopId: freshOrder.shopId, listingId: freshOrder.items[0]?.listingId, listingTitle: freshOrder.items[0]?.title,
                        appointmentStartTime: freshOrder.appointmentStartTime, status: "SCHEDULED", createdAt: now, updatedAt: now
                    });
                } else {
                    // Physical: Check reservation expirations
                    for (const res of reservationSnaps) {
                        if (res.data.status !== "ACTIVE") {
                            return { status: "RECONCILIATION_REQUIRED", reason: `RESERVATION_${res.data.status}` };
                        }
                        if (res.data.expiresAt.toMillis() < nowMs) {
                             for (const r of reservationSnaps) {
                                 transaction.update(r.ref, { status: "EXPIRED", updatedAt: now });
                                 const l = listingSnaps.find(ls => ls.ref.id === r.data.listingId)!;
                                 const lData = l.snap.data()!;
                                 const currentReserved = lData.reservedQuantity || 0;
                                 const total = lData.totalQuantity || 0;
                                 const newReserved = Math.max(0, currentReserved - r.data.quantity);
                                 transaction.update(l.ref, {
                                     reservedQuantity: newReserved,
                                     availableQuantity: total - newReserved,
                                     stockQuantity: total - newReserved,
                                     updatedAt: now
                                 });
                             }
                             transaction.update(orderDoc.ref, { status: "CANCELLED", paymentStatus: "SUCCESS", cancelReason: "TTL_EXPIRED", updatedAt: now });
                             return { status: "RECONCILIATION_REQUIRED", reason: "TTL_EXPIRED" };
                        }
                    }

                    // WRITE: COMMIT RESERVATIONS
                    for (const res of reservationSnaps) {
                        transaction.update(res.ref, { status: "COMMITTED", committedAt: now, updatedAt: now });
                        const l = listingSnaps.find(ls => ls.ref.id === res.data.listingId)!;
                        const lData = l.snap.data()!;
                        const currentTotal = lData.totalQuantity || 0;
                        const currentReserved = lData.reservedQuantity || 0;
                        const newTotal = Math.max(0, currentTotal - res.data.quantity);
                        const newReserved = Math.max(0, currentReserved - res.data.quantity);
                        transaction.update(l.ref, {
                            totalQuantity: newTotal, reservedQuantity: newReserved,
                            availableQuantity: newTotal - newReserved, stockQuantity: newTotal - newReserved,
                            updatedAt: now
                        });
                    }
                }

                // 3. COMMON WRITES (Ledger + Order)
                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId, transactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                    debitAccount: "system_mopay_clearing", creditAccount: "system_order_escrow",
                    amountMinorUnits: order.totalMinorUnits, currency: "LSL",
                    reference: `ORDER_CONFIRM_MOPAY_${order.id}`, timestamp: now
                });

                const updates: Record<string, any> = {
                    status: "CONFIRMED", paymentStatus: "PAID", inventoryStatus: "COMMITTED",
                    settlementStatus: "ESCROW_HOLD", gatewayTransactionId: mopaySession.transactionId, updatedAt: now
                };

                switch (freshOrder.type) {
                    case "FOOD_ORDER": updates.fulfillmentStatus = "PREPARING"; break;
                    case "SERVICE_BOOKING": updates.fulfillmentStatus = "READY"; break;
                    case "PRODUCT_PURCHASE":
                    case "BULK_PURCHASE": updates.fulfillmentStatus = "PREPARING"; break;
                    case "DELIVERY_REQUEST": updates.fulfillmentStatus = "PENDING"; break;
                }
                transaction.update(orderDoc.ref, updates);
                return { status: "SUCCESS" };
            });

            if (result.status === "RECONCILIATION_REQUIRED") {
                return { status: "RECONCILIATION_REQUIRED", orderId: order.id, reason: result.reason };
            }
            return { status: "SUCCESS", orderId: order.id };
        } else {
            // STEP 7: Terminal Failure OR Hold
            const isTerminalFailure = mopaySession.transactionStatus === "CANCELLED" || mopaySession.transactionStatus === "FAILED";

            if (isTerminalFailure) {
                await db.runTransaction(async (transaction) => {
                    const freshOrderDoc = await transaction.get(orderDoc.ref);
                    const freshOrder = freshOrderDoc.data()!;
                    if (freshOrder.status !== "PENDING" && freshOrder.status !== "RESERVED") return;

                    const now = admin.firestore.Timestamp.now();
                    const isService = freshOrder.type === "SERVICE_BOOKING";

                    if (isService && freshOrder.slotId) {
                        const slotRef = db.collection("availability").doc(freshOrder.shopId).collection("slots").doc(freshOrder.slotId);
                        transaction.update(slotRef, {
                            status: "AVAILABLE", reservedBy: null, expiresAt: null, orderId: null, updatedAt: now
                        });
                    } else {
                        const resQuery = await db.collection("reservations").where("orderId", "==", order.id).get();
                        for (const resDoc of resQuery.docs) {
                            const reservation = resDoc.data();
                            if (reservation.status !== "ACTIVE") continue;
                            const listingRef = db.collection("listings").doc(reservation.listingId);
                            const listingSnap = await transaction.get(listingRef);
                            transaction.update(resDoc.ref, { status: "RELEASED", releasedAt: now, updatedAt: now });
                            if (listingSnap.exists) {
                                const lData = listingSnap.data()!;
                                const newReserved = Math.max(0, (lData.reservedQuantity || 0) - reservation.quantity);
                                transaction.update(listingRef, {
                                    reservedQuantity: newReserved,
                                    availableQuantity: (lData.totalQuantity || 0) - newReserved,
                                    stockQuantity: (lData.totalQuantity || 0) - newReserved,
                                    updatedAt: now
                                });
                            }
                        }
                    }
                    transaction.update(orderDoc.ref, {
                        status: mopaySession.transactionStatus === "CANCELLED" ? "CANCELLED" : "FAILED",
                        paymentStatus: mopaySession.transactionStatus, updatedAt: now
                    });
                });
            } else if (mopaySession.transactionStatus === "PENDING") {
                await orderDoc.ref.update({ paymentStatus: "PENDING", updatedAt: admin.firestore.Timestamp.now() });
            } else {
                await orderDoc.ref.update({
                    status: "HOLD", paymentStatus: "UNKNOWN", paymentStatusRaw: mopaySession.transactionStatus,
                    updatedAt: admin.firestore.Timestamp.now()
                });
            }
            return { status: mopaySession.transactionStatus, orderId: order.id };
        }
    } catch (error: any) {
        console.error("Payment verification failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Updates order status authoritative server-side.
 */
export const updateOrderStatus = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, status } = request.data;
    if (!orderId || !status) throw new HttpsError("invalid-argument", "Missing orderId or status");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            const isAdmin = auth.token.admin === true;
            const isSeller = order.sellerId === auth.uid;
            const isBuyer = order.buyerId === auth.uid;

            if (isBuyer && status === "CANCELLED") {
                // Buyer can only cancel
            } else if (isSeller || isAdmin) {
                // Seller/Admin can update status
            } else {
                throw new Error("Unauthorized status update");
            }

            transaction.update(orderRef, {
                status,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Cancels an order with a reason.
 */
export const cancelOrder = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, reason, idempotencyKey } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            if (idempotencyKey) {
                const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
                const idempotencyDoc = await transaction.get(idempotencyRef);
                if (idempotencyDoc.exists) return;
            }

            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.buyerId !== auth.uid && order.sellerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            if (order.status === "CANCELLED" || order.status === "REFUNDED") return;

            if (order.paymentMethod === "SWIFT_WALLET" && order.status === "CONFIRMED") {
                const walletRef = db.collection("wallets").doc(order.buyerId);
                const walletDoc = await transaction.get(walletRef);
                if (walletDoc.exists) {
                    const currentBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                    transaction.update(walletRef, {
                        availableBalanceMinorUnits: currentBalance + order.totalMinorUnits,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });
                    const ledgerId = db.collection("ledgerEntries").doc().id;
                    transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                        id: ledgerId, debitAccount: "system_order_escrow", creditAccount: `user_${order.buyerId}`,
                        amountMinorUnits: order.totalMinorUnits, currency: "LSL", reference: `ORDER_REFUND_${orderId}`,
                        timestamp: admin.firestore.FieldValue.serverTimestamp()
                    });
                }
            }

            const isService = order.type === "SERVICE_BOOKING";
            const reservationSnaps = [];
            const listingSnaps = [];
            let slotRef = null;

            if (isService && order.slotId) {
                slotRef = db.collection("availability").doc(order.shopId).collection("slots").doc(order.slotId);
            } else {
                const resQuery = await db.collection("reservations").where("orderId", "==", order.id).get();
                for (const resDoc of resQuery.docs) {
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") continue;
                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = await transaction.get(listingRef);
                    reservationSnaps.push({ ref: resDoc.ref, data: reservation });
                    listingSnaps.push({ ref: listingRef, snap: listingSnap });
                }
            }

            const now = admin.firestore.Timestamp.now();
            if (isService && slotRef) {
                transaction.update(slotRef, {
                    status: "AVAILABLE", reservedBy: null, expiresAt: null, orderId: null, updatedAt: now
                });
            } else {
                for (const { ref, data } of reservationSnaps) {
                    transaction.update(ref, { status: "RELEASED", releasedAt: now, updatedAt: now });
                    const l = listingSnaps.find(ls => ls.ref.id === data.listingId)!;
                    const lData = l.snap.data()!;
                    const newReserved = Math.max(0, (lData.reservedQuantity || 0) - data.quantity);
                    transaction.update(l.ref, {
                        reservedQuantity: newReserved,
                        availableQuantity: (lData.totalQuantity || 0) - newReserved,
                        stockQuantity: (lData.totalQuantity || 0) - newReserved,
                        updatedAt: now
                    });
                }
            }

            transaction.update(orderRef, { status: "CANCELLED", cancelReason: reason || "User requested", updatedAt: now });
            if (idempotencyKey) {
                const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
                transaction.set(idempotencyRef, { orderId, userId: auth.uid, action: "CANCEL_ORDER", createdAt: now });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * DEVELOPMENT ONLY: Confirms Mopay payment (Admin gated).
 */
export const confirmMopayPayment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth || !auth.token.admin) throw new HttpsError("permission-denied", "Admin only");

    const { orderId, paymentId, idempotencyKey } = request.data;
    if (!orderId || !paymentId) throw new HttpsError("invalid-argument", "Missing orderId or paymentId");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            if (idempotencyKey) {
                const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
                const idempotencyDoc = await transaction.get(idempotencyRef);
                if (idempotencyDoc.exists) return;
            }

            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.status === "CONFIRMED") return;
            if (order.status !== "PENDING" && order.status !== "RESERVED") {
                throw new Error(`Order is in state ${order.status} and cannot be confirmed.`);
            }

            const isService = order.type === "SERVICE_BOOKING";
            const now = admin.firestore.Timestamp.now();

            if (isService) {
                const slotId = order.slotId;
                if (slotId) {
                    const slotRef = db.collection("availability").doc(order.shopId).collection("slots").doc(slotId);
                    const slotSnap = await transaction.get(slotRef);
                    if (slotSnap.exists && slotSnap.data()?.status === "RESERVED") {
                        transaction.update(slotRef, { status: "BOOKED", updatedAt: now });
                        const appointmentRef = db.collection("appointments").doc(orderId);
                        transaction.set(appointmentRef, {
                            id: orderId, orderId, buyerId: order.buyerId, sellerId: order.sellerId, shopId: order.shopId,
                            listingId: order.items[0]?.listingId, listingTitle: order.items[0]?.title,
                            appointmentStartTime: order.appointmentStartTime, status: "SCHEDULED", createdAt: now, updatedAt: now
                        });
                    }
                }
            } else {
                const resQuery = await db.collection("reservations").where("orderId", "==", orderId).get();
                for (const resDoc of resQuery.docs) {
                    const resData = resDoc.data();
                    if (resData.status === "ACTIVE") {
                        transaction.update(resDoc.ref, { status: "COMMITTED", committedAt: now, updatedAt: now });
                        const listingRef = db.collection("listings").doc(resData.listingId);
                        const lSnap = await transaction.get(listingRef);
                        if (lSnap.exists) {
                            const lData = lSnap.data()!;
                            const newTotal = Math.max(0, (lData.totalQuantity || 0) - resData.quantity);
                            const newReserved = Math.max(0, (lData.reservedQuantity || 0) - resData.quantity);
                            transaction.update(listingRef, {
                                totalQuantity: newTotal, reservedQuantity: newReserved,
                                availableQuantity: newTotal - newReserved, stockQuantity: newTotal - newReserved,
                                updatedAt: now
                            });
                        }
                    }
                }
            }

            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId, transactionId: paymentId, debitAccount: "system_mopay_clearing", creditAccount: "system_order_escrow",
                amountMinorUnits: order.totalMinorUnits, currency: "LSL", reference: `ORDER_CONFIRM_MANUAL_${orderId}`, timestamp: now
            });

            transaction.update(orderRef, { status: "CONFIRMED", paymentId, paymentStatus: "SUCCESS", updatedAt: now });
            if (idempotencyKey) {
                const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
                transaction.set(idempotencyRef, { orderId, userId: auth.uid, action: "CONFIRM_PAYMENT", createdAt: now });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Initiates a tier upgrade subscription request.
 */
export const initiateSubscription = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { targetTier } = request.data;
    if (!["PREMIUM", "ELITE"].includes(targetTier)) {
        throw new HttpsError("invalid-argument", "Invalid target tier");
    }

    const db = admin.firestore();
    const subscriptionId = db.collection("subscriptions").doc().id;

    await db.collection("subscriptions").doc(subscriptionId).set({
        id: subscriptionId,
        userId: auth.uid,
        tier: targetTier,
        status: "PENDING_PAYMENT",
        createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return subscriptionId;
});

/**
 * Executes the atomic reservation of a slot and the creation of a service booking.
 */
export const initiateServiceBooking = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId, slotId, paymentMethod, provider, phoneNumber, idempotencyKey } = request.data;
    if (!listingId || !slotId || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing required booking parameters");
    }

    const db = admin.firestore();
    const serverNow = admin.firestore.Timestamp.now();
    const nowMs = serverNow.toMillis();

    try {
        const result = await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("bookingIdempotency").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                const data = idempotencyDoc.data()!;
                if (data.buyerId !== auth.uid) throw new Error("Idempotency key collision");
                return { orderId: data.orderId, alreadyExists: true };
            }

            const listingRef = db.collection("listings").doc(listingId);
            const listingSnap = await transaction.get(listingRef);
            if (!listingSnap.exists) throw new Error("Listing not found");
            const listing = listingSnap.data()!;

            if (listing.listingType !== "SET_APPOINTMENT" && listing.listingType !== "BOOKABLE_SERVICE") {
                throw new Error("Listing is not a bookable service");
            }

            const shopId = listing.shopId;
            const priceMinorUnits = listing.priceMinorUnits || 0;
            const currency = listing.priceCurrency || "LSL";
            const title = listing.title || "Service";

            const slotRef = db.collection("availability").doc(shopId).collection("slots").doc(slotId);
            const slotSnap = await transaction.get(slotRef);
            if (!slotSnap.exists) throw new Error("Slot not found");
            const slot = slotSnap.data()!;

            const isAvailable = slot.status === "AVAILABLE" || (slot.status === "RESERVED" && (slot.expiresAt || 0) < nowMs);
            if (!isAvailable) throw new Error("Slot is no longer available");

            const orderId = db.collection("orders").doc().id;
            const startTime = slot.startTime || 0;
            const startDate = new Date(startTime);
            const clientPayload = request.data.payload || {};

            const orderData = {
                id: orderId, buyerId: auth.uid, sellerId: listing.sellerId, shopId: shopId,
                type: "SERVICE_BOOKING",
                participants: { "REQUESTER": auth.uid, "LISTING_AUTHOR": listing.sellerId, "SERVICE_PROVIDER": listing.sellerId },
                status: "PENDING", fulfillmentType: "SERVICE", slotId: slotId, appointmentStartTime: startTime,
                totalMinorUnits: priceMinorUnits, currency: currency, idempotencyKey,
                paymentMethod: paymentMethod || "MOPAY", provider: provider || null, phoneNumber,
                createdAt: admin.firestore.FieldValue.serverTimestamp(), updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                items: [{ listingId, title, quantity: 1, unitPriceMinorUnits: priceMinorUnits, unitPriceCurrency: currency }],
                payload: {
                    serviceId: listingId, requestedDate: startDate.toISOString().split("T")[0],
                    requestedTime: startDate.toISOString().split("T")[1].substring(0, 5),
                    durationMinutes: listing.durationMinutes || 0, locationType: clientPayload.locationType || "ON_SITE"
                }
            };

            transaction.set(db.collection("orders").doc(orderId), orderData);
            transaction.update(slotRef, {
                status: "RESERVED", reservedBy: auth.uid, expiresAt: nowMs + (15 * 60 * 1000), orderId,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            transaction.set(idempotencyRef, { orderId, buyerId: auth.uid, listingId, slotId, createdAt: serverNow });

            return { orderId, total: priceMinorUnits, title, shopId, alreadyExists: false };
        });

        const { orderId, total, title, shopId, alreadyExists } = result;
        if (alreadyExists) return { orderId };

        if (paymentMethod === "MOPAY" && orderId) {
            const mopayRequest = {
                amount: (total / 100).toFixed(2), reference: orderId, redirectUrl: "swiftshop://checkout/verify",
                description: `Booking: ${title}`, customerEmail: auth.token.email, customerName: auth.token.name || auth.uid,
            };
            const mopayResponse = await MopayClient.initiatePaymentSession(mopayRequest);
            if (mopayResponse.success && mopayResponse.sessionId) {
                await db.collection("orders").doc(orderId).update({
                    mopaySessionId: mopayResponse.sessionId, paymentUrl: mopayResponse.paymentUrl,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
                return { orderId, paymentUrl: mopayResponse.paymentUrl, mopaySessionId: mopayResponse.sessionId };
            } else {
                await db.runTransaction(async (transaction) => {
                    const slotRef = db.collection("availability").doc(shopId).collection("slots").doc(slotId);
                    const slotSnap = await transaction.get(slotRef);
                    if (slotSnap.exists && slotSnap.data()?.orderId === orderId) {
                        transaction.update(slotRef, { status: "AVAILABLE", reservedBy: null, expiresAt: null, orderId: null, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
                    }
                    transaction.update(db.collection("orders").doc(orderId), { status: "FAILED", error: mopayResponse.message || "Gateway failure", updatedAt: admin.firestore.FieldValue.serverTimestamp() });
                });
                return { orderId, error: mopayResponse.message || "Gateway failure" };
            }
        }
        return { orderId };
    } catch (error: any) {
        console.error("Booking initiation failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Creates a listing with tier limit enforcement.
 */
export const createListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const listing = request.data;
    if (!listing.shopId) throw new HttpsError("invalid-argument", "shopId required");

    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const shopRef = db.collection("shops").doc(listing.shopId);
            const shopDoc = await transaction.get(shopRef);
            if (!shopDoc.exists) throw new Error("Shop not found");
            const shop = shopDoc.data()!;

            if (shop.ownerId !== uid) throw new Error("Unauthorized");

            const profileRef = db.collection("profiles").doc(uid);
            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) throw new Error("Profile not found");
            const activeListingCount = profileDoc.data()!.activeListingCount || 0;

            const listingId = db.collection("listings").doc().id;
            const isAvailable = listing.isAvailable !== false;

            const requestedQty = parseInt(listing.stockQuantity || listing.totalQuantity || "0");
            const initialQuantity = isNaN(requestedQty) ? 0 : Math.max(0, requestedQty);

            const newListing = {
                ...listing, id: listingId, sellerId: uid, isAvailable,
                totalQuantity: initialQuantity, reservedQuantity: 0, availableQuantity: initialQuantity, stockQuantity: initialQuantity,
                createdAt: admin.firestore.FieldValue.serverTimestamp(), updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };

            transaction.set(db.collection("listings").doc(listingId), newListing);
            transaction.update(shopRef, { listingCount: (shop.listingCount || 0) + 1, updatedAt: admin.firestore.FieldValue.serverTimestamp() });

            if (isAvailable) {
                transaction.update(profileRef, { activeListingCount: activeListingCount + 1, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
            }
            return listingId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Deletes a listing and updates shop counter.
 */
export const deleteListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);
            if (!listingDoc.exists) throw new Error("Listing not found");
            const listing = listingDoc.data()!;

            if (listing.sellerId !== auth.uid && !auth.token.admin) throw new Error("Unauthorized");

            const shopRef = db.collection("shops").doc(listing.shopId);
            const shopDoc = await transaction.get(shopRef);
            const profileRef = db.collection("profiles").doc(listing.sellerId);
            const profileDoc = await transaction.get(profileRef);

            transaction.delete(listingRef);
            if (shopDoc.exists) {
                transaction.update(shopRef, { listingCount: Math.max(0, (shopDoc.data()!.listingCount || 0) - 1), updatedAt: admin.firestore.FieldValue.serverTimestamp() });
            }
            if (profileDoc.exists && listing.isAvailable) {
                transaction.update(profileRef, { activeListingCount: Math.max(0, (profileDoc.data()!.activeListingCount || 0) - 1), updatedAt: admin.firestore.FieldValue.serverTimestamp() });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Deletes a shop and cascades to all of its listings.
 */
export const deleteShop = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { shopId } = request.data;
    if (!shopId) throw new HttpsError("invalid-argument", "shopId required");

    const db = admin.firestore();
    const shopRef = db.collection("shops").doc(shopId);

    try {
        const shopDoc = await shopRef.get();
        if (!shopDoc.exists) throw new HttpsError("not-found", "Shop not found");
        const shop = shopDoc.data()!;

        if (shop.ownerId !== auth.uid && !auth.token.admin) throw new HttpsError("permission-denied", "Unauthorized");

        const listingsSnapshot = await db.collection("listings").where("shopId", "==", shopId).get();
        let activeListingsDeleted = 0;
        const listingDocs = listingsSnapshot.docs;
        const BATCH_SIZE = 400;

        for (let i = 0; i < listingDocs.length; i += BATCH_SIZE) {
            const chunk = listingDocs.slice(i, i + BATCH_SIZE);
            const batch = db.batch();
            for (const doc of chunk) {
                if (doc.data().isAvailable) activeListingsDeleted++;
                batch.delete(doc.ref);
            }
            await batch.commit();
        }

        await db.runTransaction(async (transaction) => {
            const profileRef = db.collection("profiles").doc(shop.ownerId);
            const profileDoc = await transaction.get(profileRef);
            transaction.delete(shopRef);
            if (profileDoc.exists) {
                transaction.update(profileRef, {
                    shopCount: Math.max(0, (profileDoc.data()!.shopCount || 0) - 1),
                    activeListingCount: Math.max(0, (profileDoc.data()!.activeListingCount || 0) - activeListingsDeleted),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
        return { success: true, listingsDeleted: listingDocs.length };
    } catch (error: any) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Creates a shop with tier limit enforcement.
 */
export const createShop = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const shop = request.data;
    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const userDoc = await transaction.get(db.collection("users").doc(uid));
            if (!userDoc.exists) throw new Error("User not found");
            const tier = userDoc.data()!.tier || "BASIC";

            const profileRef = db.collection("profiles").doc(uid);
            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) throw new Error("Profile not found");

            if (shop.id) {
                const existingDoc = await transaction.get(db.collection("shops").doc(shop.id));
                if (existingDoc.exists) {
                    if (existingDoc.data()!.ownerId === uid) return shop.id;
                    throw new Error("Shop ID already taken");
                }
            }

            const shopsQuery = db.collection("shops").where("ownerId", "==", uid);
            const shopsSnapshot = await transaction.get(shopsQuery);
            const realShopCount = shopsSnapshot.size;

            let maxShops = 1;
            if (tier === "PREMIUM") maxShops = 3;
            if (tier === "ELITE") maxShops = -1;

            if (maxShops !== -1 && realShopCount >= maxShops) throw new Error(`Shop limit reached for ${tier} tier.`);

            const shopId = shop.id || db.collection("shops").doc().id;
            const now = admin.firestore.FieldValue.serverTimestamp();
            const newShop = { ...shop, id: shopId, ownerId: uid, isVerified: false, createdAt: now, updatedAt: now };

            transaction.set(db.collection("shops").doc(shopId), newShop);
            transaction.update(profileRef, { shopCount: realShopCount + 1, updatedAt: now });

            return shopId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Updates a listing and maintains active count.
 */
export const updateListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId, updates } = request.data;
    if (!listingId || !updates) throw new HttpsError("invalid-argument", "Missing data");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);
            if (!listingDoc.exists) throw new Error("Listing not found");
            const listing = listingDoc.data()!;

            if (listing.sellerId !== auth.uid && !auth.token.admin) throw new Error("Unauthorized");

            const shopRef = db.collection("shops").doc(listing.shopId);
            const shopDoc = await transaction.get(shopRef);
            if (!shopDoc.exists) throw new Error("Parent shop not found");
            if (shopDoc.data()?.ownerId !== auth.uid && !auth.token.admin) throw new Error("Unauthorized");

            const allowedFields = [
                "title", "description", "category", "priceMinorUnits", "priceCurrency",
                "imageUrls", "videoUrl", "tags", "isAvailable", "isSponsored",
                "deliveryEstimateDays", "customFields", "totalQuantity"
            ];

            const filteredUpdates: Record<string, any> = {};
            for (const key of Object.keys(updates)) {
                if (allowedFields.includes(key)) filteredUpdates[key] = updates[key];
            }

            const currentReserved = listing.reservedQuantity || 0;
            const newTotalRequested = filteredUpdates.totalQuantity;

            if (newTotalRequested !== undefined) {
                const newTotal = parseInt(newTotalRequested);
                if (isNaN(newTotal) || newTotal < 0) throw new Error("Invalid totalQuantity");
                if (newTotal < currentReserved) throw new Error(`Cannot reduce stock below ${currentReserved} reserved units.`);
                filteredUpdates.totalQuantity = newTotal;
                filteredUpdates.availableQuantity = newTotal - currentReserved;
                filteredUpdates.stockQuantity = newTotal - currentReserved;
            }

            const oldAvailable = listing.isAvailable !== false;
            const newAvailable = filteredUpdates.isAvailable !== undefined ? filteredUpdates.isAvailable : oldAvailable;

            filteredUpdates.updatedAt = admin.firestore.FieldValue.serverTimestamp();
            transaction.update(listingRef, filteredUpdates);

            if (oldAvailable !== newAvailable) {
                const profileRef = db.collection("profiles").doc(listing.sellerId);
                const profileDoc = await transaction.get(profileRef);
                if (profileDoc.exists) {
                    const currentCount = profileDoc.data()!.activeListingCount || 0;
                    transaction.update(profileRef, { activeListingCount: newAvailable ? currentCount + 1 : Math.max(0, currentCount - 1), updatedAt: admin.firestore.FieldValue.serverTimestamp() });
                }
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});
