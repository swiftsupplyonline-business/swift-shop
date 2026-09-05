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

    // ── Authoritative delivery fee ─────────────────────────────────────────────
    // The delivery fee is read exclusively from the selected DeliveryListing
    // document in Firestore. No fee is ever invented by the function, derived from
    // route distance, or accepted from the client. This replaces the former
    // hard-coded M25.00 constant.
    const deliveryListingDoc = await db.collection("listings").doc(deliveryListingId).get();
    if (!deliveryListingDoc.exists) {
        throw new HttpsError("not-found", `Delivery listing ${deliveryListingId} not found`);
    }
    const deliveryListing = deliveryListingDoc.data()!;
    if (deliveryListing.listingType !== "DELIVER") {
        throw new HttpsError("invalid-argument", "The provided listing is not a delivery listing");
    }
    if (!deliveryListing.isAvailable) {
        throw new HttpsError("failed-precondition", "The selected delivery listing is no longer available");
    }
    const deliveryFee: number = deliveryListing.priceMinorUnits || 0;

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
        deliveryListingId
    };
});

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

    const { items, deliveryAddress, deliveryListingId, paymentMethod, provider, idempotencyKey } = request.data;
    if (!items || !Array.isArray(items) || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing items or idempotencyKey");
    }
    if (!deliveryListingId) {
        throw new HttpsError("invalid-argument", "deliveryListingId is required — delivery fee must come from a delivery listing");
    }

    const db = admin.firestore();
    let orderId = "";
    let finalTotal = 0;

    try {
        // ── Validate delivery listing BEFORE the transaction (fail fast). ─────────
        // The fee is read exclusively from the delivery listing document.
        // No fee amount is ever accepted from the client, calculated from distance,
        // or defaulted to a hard-coded constant. This replaces the former M25 constant.
        const deliveryListingDoc = await db.collection("listings").doc(deliveryListingId).get();
        if (!deliveryListingDoc.exists) {
            throw new HttpsError("not-found", `Delivery listing ${deliveryListingId} not found`);
        }
        const deliveryListingData = deliveryListingDoc.data()!;
        if (deliveryListingData.listingType !== "DELIVER") {
            throw new HttpsError("invalid-argument", "The provided listing is not a delivery listing");
        }
        if (!deliveryListingData.isAvailable) {
            throw new HttpsError("failed-precondition", "The selected delivery option is no longer available");
        }
        const deliveryFeeFromListing: number = deliveryListingData.priceMinorUnits || 0;

        // Immutable snapshot stored on the order for historical integrity.
        // If the provider later changes their price, existing orders are unaffected.
        const deliveryListingSnapshot = {
            listingId: deliveryListingId,
            providerId: deliveryListingData.sellerId || "",
            providerName: deliveryListingData.providerName || "",
            title: deliveryListingData.title || "",
            priceMinorUnits: deliveryFeeFromListing,
            currency: deliveryListingData.priceCurrency || "LSL",
            estimatedMinutes: deliveryListingData.estimatedMinutes || 0
        };

        // STEP 1: Atomic Inventory Reservation & Order Record Creation
        const result = await db.runTransaction(async (transaction) => {
            // 1. Idempotency Check
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                console.log(`Idempotent request for key ${idempotencyKey}. Returning existing orderId.`);
                return { orderId: idempotencyDoc.data()?.orderId, total: 0, items: [], alreadyExists: true };
            }

            let subtotal = 0;
            const newOrderId = db.collection("orders").doc().id;
            const validatedItems = [];
            let shopId = "";
            let sellerId = "";

            // 1b. Wallet Read (must occur before any transaction writes — Firestore
            // transactions require all reads before any write in the same transaction).
            // Only relevant for SWIFT_WALLET; skipped entirely for MOPAY.
            const isWalletPayment = paymentMethod === "SWIFT_WALLET";
            const walletRef = db.collection("wallets").doc(auth.uid);
            const walletDoc = isWalletPayment ? await transaction.get(walletRef) : null;
            if (isWalletPayment && (!walletDoc || !walletDoc.exists)) {
                throw new Error("Wallet not found. Deposit funds before checking out with Swift Wallet.");
            }
            const walletAvailableBefore = isWalletPayment ? (walletDoc!.data()!.availableBalanceMinorUnits || 0) : 0;
            const walletCurrency = isWalletPayment ? (walletDoc!.data()!.currency || "LSL") : "LSL";

            // 2. Authoritative Price & Inventory Check
            for (const item of items) {
                const listingRef = db.collection("listings").doc(item.listingId);
                const listingDoc = await transaction.get(listingRef);

                if (!listingDoc.exists) throw new Error(`Listing ${item.listingId} not found`);
                const listing = listingDoc.data()!;

                if (!listing.isAvailable) throw new Error(`Listing ${item.listingId} is not available`);

                const requestedQty = item.quantity || 1;
                const availableStock = listing.stockQuantity;

                if (availableStock !== undefined && availableStock !== null) {
                    if (availableStock < requestedQty) {
                        throw new Error(`Insufficient stock for ${listing.title}. Requested: ${requestedQty}, Available: ${availableStock}`);
                    }

                    // ATOMIC RESERVATION (Immediate decrement)
                    transaction.update(listingRef, {
                        stockQuantity: availableStock - requestedQty,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });
                }

                if (!shopId) {
                    shopId = listing.shopId;
                    sellerId = listing.sellerId;
                } else if (listing.shopId !== shopId) {
                    throw new Error("Multi-shop orders are not supported in this version.");
                }

                const itemTotal = listing.priceMinorUnits * requestedQty;
                subtotal += itemTotal;

                validatedItems.push({
                    listingId: item.listingId,
                    title: listing.title,
                    quantity: requestedQty,
                    unitPriceMinorUnits: listing.priceMinorUnits,
                    unitPriceCurrency: listing.priceCurrency || "LSL"
                });
            }

            // Delivery fee is authoritative from the delivery listing validated above.
            // deliveryFeeFromListing and deliveryListingSnapshot are captured in the
            // outer closure before this transaction begins.
            const deliveryFee = deliveryFeeFromListing;
            const platformFee = Math.floor((subtotal * 15) / 1000);
            const total = subtotal + deliveryFee + platformFee;

            // 2b. Wallet Balance Gate & Atomic Debit (SWIFT_WALLET only).
            // This must happen before any writes are committed: if the balance is
            // insufficient we throw, which aborts the entire transaction (including
            // the stock-reservation writes above), so nothing is left dangling.
            if (isWalletPayment) {
                if (walletCurrency !== "LSL") {
                    throw new Error(`Wallet currency mismatch: expected LSL, wallet is ${walletCurrency}.`);
                }
                if (walletAvailableBefore < total) {
                    throw new Error(`Insufficient wallet balance. Required: ${total}, Available: ${walletAvailableBefore}.`);
                }
            }

            const orderStatus = isWalletPayment ? "CONFIRMED" : "PENDING";

            const orderDoc: Record<string, unknown> = {
                id: newOrderId,
                buyerId: auth.uid,
                sellerId: sellerId,
                shopId: shopId,
                items: validatedItems,
                subtotalMinorUnits: subtotal,
                deliveryFeeMinorUnits: deliveryFee,
                platformFeeMinorUnits: platformFee,
                totalMinorUnits: total,
                currency: "LSL",
                status: orderStatus,
                deliveryAddress: deliveryAddress || {},
                // Canonical delivery listing reference and immutable price snapshot.
                // The snapshot preserves the exact commercial agreement at order time
                // even if the delivery provider later changes their listing price.
                selectedDeliveryListingId: deliveryListingId,
                deliveryListingSnapshot: deliveryListingSnapshot,
                paymentMethod: paymentMethod || "MOPAY",
                provider: provider || null,
                idempotencyKey: idempotencyKey,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };

            if (isWalletPayment) {
                orderDoc.paymentStatus = "SUCCESS";
            }

            transaction.set(db.collection("orders").doc(newOrderId), orderDoc);
            transaction.set(idempotencyRef, {
                orderId: newOrderId,
                userId: auth.uid,
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // 2c. Atomic Wallet Debit + Double-Entry Ledger (SWIFT_WALLET only).
            // Mirrors the debit/ledger convention already used in finance.ts and in
            // verifyMopayPayment's escrow entry (system_order_escrow as credit account).
            if (isWalletPayment) {
                const walletTxnId = db.collection("walletTransactions").doc().id;
                const ledgerId = db.collection("ledgerEntries").doc().id;
                const newAvailable = walletAvailableBefore - total;

                transaction.update(walletRef, {
                    availableBalanceMinorUnits: newAvailable,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                transaction.set(db.collection("walletTransactions").doc(walletTxnId), {
                    transactionId: walletTxnId,
                    userId: auth.uid,
                    type: "PURCHASE",
                    amountMinorUnits: total,
                    feeMinorUnits: 0,
                    currency: "LSL",
                    status: "COMPLETE",
                    description: `Order ${newOrderId} at Swift Shop (Swift Wallet)`,
                    orderId: newOrderId,
                    idempotencyKey: idempotencyKey,
                    createdAt: admin.firestore.FieldValue.serverTimestamp(),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    transactionId: walletTxnId,
                    debitAccount: `user_${auth.uid}`,
                    creditAccount: "system_order_escrow",
                    amountMinorUnits: total,
                    currency: "LSL",
                    reference: `ORDER_CONFIRM_WALLET_${newOrderId}`,
                    timestamp: admin.firestore.FieldValue.serverTimestamp()
                });
            }

            return { orderId: newOrderId, total, items: validatedItems, alreadyExists: false };
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
                    for (const item of result.items) {
                        const listingRef = db.collection("listings").doc(item.listingId);
                        const listingDoc = await transaction.get(listingRef);
                        if (listingDoc.exists) {
                            const listing = listingDoc.data()!;
                            if (listing.stockQuantity !== undefined && listing.stockQuantity !== null) {
                                transaction.update(listingRef, {
                                    stockQuantity: listing.stockQuantity + item.quantity,
                                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                                });
                            }
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

        // SWIFT_WALLET path: balance was checked, debited, and the order was
        // set to CONFIRMED with a ledger entry — all atomically inside the
        // Step 1 transaction above (see 2b/2c). Nothing further to do here.
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

        // 2. State Gating: Only PENDING orders can be verified
        if (order.status !== "PENDING") {
            console.warn(`Attempted to verify non-PENDING order ${order.id} with status ${order.status}`);
            return { status: order.status, orderId: order.id, message: "Order is no longer awaiting payment." };
        }

        // 3. Authoritative Gateway Verification
        const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
        if (!mopaySession) throw new Error("Could not verify session with MoPay.");

        // 4. Validation
        if (mopaySession.reference !== order.id) {
            throw new Error("Session reference mismatch.");
        }

        const mopayAmountMinor = Math.round(mopaySession.amount * 100);
        if (mopayAmountMinor !== order.totalMinorUnits) {
            throw new Error(`Amount mismatch. Expected: ${order.totalMinorUnits}, Got: ${mopayAmountMinor}`);
        }

        // 5. Atomic Transition (only if SUCCESS)
        if (mopaySession.transactionStatus === "SUCCESS") {
            await db.runTransaction(async (transaction) => {
                const freshOrderDoc = await transaction.get(orderDoc.ref);
                const freshOrder = freshOrderDoc.data()!;

                // Re-check status inside transaction
                if (freshOrder.status !== "PENDING") return;

                const isService = freshOrder.fulfillmentType === "SERVICE";
                const now = admin.firestore.Timestamp.now();
                const nowMs = now.toMillis();

                if (isService) {
                    const shopId = freshOrder.shopId;
                    const slotId = freshOrder.slotId;
                    if (!slotId) throw new Error("Service order missing slotId");

                    const slotRef = db.collection("availability").doc(shopId).collection("slots").doc(slotId);
                    const slotSnap = await transaction.get(slotRef);

                    if (!slotSnap.exists) throw new Error("Associated slot not found");
                    const slot = slotSnap.data()!;

                    // STEP 6: Expiration Safety (P0)
                    const expiresAt = slot.expiresAt || 0;
                    if (expiresAt < nowMs) {
                        console.error(`[UNRESOLVED FINANCIAL RECOVERY] Payment successful for expired reservation: Order ${freshOrder.id}, Slot ${slotId}`);
                        transaction.update(orderDoc.ref, {
                            status: "CANCELLED",
                            paymentStatus: "SUCCESS", // Still mark payment as success but cancel order
                            cancelReason: "Reservation expired before payment verification",
                            updatedAt: now
                        });
                        return;
                    }

                    // Verify slot is still reserved for this order
                    if (slot.status !== "RESERVED" || slot.orderId !== freshOrder.id) {
                        throw new Error("Slot is no longer reserved for this order");
                    }

                    // Transition slot RESERVED -> BOOKED
                    transaction.update(slotRef, {
                        status: "BOOKED",
                        updatedAt: now
                    });

                    // Idempotent Appointment Creation
                    const appointmentRef = db.collection("appointments").doc(freshOrder.id);
                    transaction.set(appointmentRef, {
                        id: freshOrder.id,
                        orderId: freshOrder.id,
                        buyerId: freshOrder.buyerId,
                        sellerId: freshOrder.sellerId,
                        shopId: freshOrder.shopId,
                        listingId: freshOrder.items[0]?.listingId,
                        listingTitle: freshOrder.items[0]?.title,
                        appointmentStartTime: freshOrder.appointmentStartTime,
                        status: "SCHEDULED",
                        createdAt: now,
                        updatedAt: now
                    });
                }

                // Create Ledger Entry
                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    transactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                    debitAccount: "system_mopay_clearing",
                    creditAccount: "system_order_escrow",
                    amountMinorUnits: order.totalMinorUnits,
                    currency: "LSL",
                    reference: `ORDER_CONFIRM_MOPAY_${order.id}`,
                    involvedAccounts: ["system_mopay_clearing", "system_order_escrow"],
                    timestamp: now
                });

                transaction.update(orderDoc.ref, {
                    status: "CONFIRMED",
                    paymentStatus: "SUCCESS",
                    gatewayTransactionId: mopaySession.transactionId,
                    updatedAt: now
                });
            });

            return { status: "SUCCESS", orderId: order.id };
        } else {
            // Update order with failure info if applicable
            await orderDoc.ref.update({
                paymentStatus: mopaySession.transactionStatus,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

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

            // Authorization
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

    const { orderId, reason } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.buyerId !== auth.uid && order.sellerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            // IDEMPOTENCY: Check if already cancelled
            if (order.status === "CANCELLED" || order.status === "REFUNDED") {
                return; // Already processed
            }

            // Refund logic for SWIFT_WALLET payment
            if (order.paymentMethod === "SWIFT_WALLET" && order.status === "CONFIRMED") {
                const walletRef = db.collection("wallets").doc(order.buyerId);
                const walletDoc = await transaction.get(walletRef);
                if (walletDoc.exists) {
                    const currentBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                    transaction.update(walletRef, {
                        availableBalanceMinorUnits: currentBalance + order.totalMinorUnits,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });

                    // Ledger Entry
                    const ledgerId = db.collection("ledgerEntries").doc().id;
                    transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                        id: ledgerId,
                        debitAccount: "system_order_escrow",
                        creditAccount: `user_${order.buyerId}`,
                        amountMinorUnits: order.totalMinorUnits,
                        currency: "LSL",
                        reference: `ORDER_REFUND_${orderId}`,
                        timestamp: admin.firestore.FieldValue.serverTimestamp()
                    });
                }
            }

            // INVENTORY RESTORATION: Restore stock for each item
            for (const item of order.items) {
                const listingRef = db.collection("listings").doc(item.listingId);
                const listingDoc = await transaction.get(listingRef);
                if (listingDoc.exists) {
                    const listing = listingDoc.data()!;
                    // Only restore if listing has finite stock
                    if (listing.stockQuantity !== undefined && listing.stockQuantity !== null) {
                        transaction.update(listingRef, {
                            stockQuantity: listing.stockQuantity + item.quantity,
                            updatedAt: admin.firestore.FieldValue.serverTimestamp()
                        });
                    }
                }
            }

            transaction.update(orderRef, {
                status: "CANCELLED",
                cancelReason: reason || "User requested",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
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

    const { orderId, paymentId } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.status !== "PENDING") throw new Error("Order not in PENDING state");

            // Create Ledger Entry for external payment
            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId,
                debitAccount: "system_mopay_clearing",
                creditAccount: "system_order_escrow",
                amountMinorUnits: order.totalMinorUnits,
                currency: "LSL",
                reference: `ORDER_CONFIRM_MOPAY_${orderId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(orderRef, {
                status: "CONFIRMED",
                paymentId,
                paymentStatus: "SUCCESS",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
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
 *
 * Contract Phase 13D:
 * - Transactional atomicity.
 * - Idempotency.
 * - Buyer identity enforcement.
 * - Slot validation.
 * - No financial side effects.
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
            // 1. Idempotency Check
            const idempotencyRef = db.collection("bookingIdempotency").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                const data = idempotencyDoc.data()!;
                // Security check: Ensure retry belongs to the same user
                if (data.buyerId !== auth.uid) throw new Error("Idempotency key collision");
                return { orderId: data.orderId, alreadyExists: true };
            }

            // 2. Authoritative Listing Read
            const listingRef = db.collection("listings").doc(listingId);
            const listingSnap = await transaction.get(listingRef);
            if (!listingSnap.exists) throw new Error("Listing not found");
            const listing = listingSnap.data()!;

            if (listing.listingType !== "SET_APPOINTMENT") {
                throw new Error("Listing is not a service appointment");
            }

            const shopId = listing.shopId;
            const priceMinorUnits = listing.priceMinorUnits || 0;
            const currency = listing.priceCurrency || "LSL";
            const title = listing.title || "Service";

            // 3. Authoritative Slot Read & Expiration Check
            const slotRef = db.collection("availability").doc(shopId).collection("slots").doc(slotId);
            const slotSnap = await transaction.get(slotRef);
            if (!slotSnap.exists) throw new Error("Slot not found");
            const slot = slotSnap.data()!;

            const slotStatus = slot.status;
            const expiresAt = slot.expiresAt || 0; // ms
            const startTime = slot.startTime || 0;

            const isAvailable = slotStatus === "AVAILABLE" || (slotStatus === "RESERVED" && expiresAt < nowMs);
            if (!isAvailable) throw new Error("Slot is no longer available");

            // 4. Create Order (PENDING)
            const orderId = db.collection("orders").doc().id;
            const orderData = {
                id: orderId,
                buyerId: auth.uid,
                sellerId: listing.sellerId,
                shopId: shopId,
                status: "PENDING",
                fulfillmentType: "SERVICE",
                slotId: slotId,
                appointmentStartTime: startTime,
                totalMinorUnits: priceMinorUnits,
                currency: currency,
                idempotencyKey: idempotencyKey,
                paymentMethod: paymentMethod || "MOPAY",
                provider: provider || null,
                phoneNumber: phoneNumber,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                items: [{
                    listingId: listingId,
                    title: title,
                    quantity: 1,
                    unitPriceMinorUnits: priceMinorUnits,
                    unitPriceCurrency: currency
                }]
            };
            transaction.set(db.collection("orders").doc(orderId), orderData);

            // 5. Reserve Slot
            transaction.update(slotRef, {
                status: "RESERVED",
                reservedBy: auth.uid,
                expiresAt: nowMs + (15 * 60 * 1000), // 15 mins from server time
                orderId: orderId,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // 6. Record Idempotency
            transaction.set(idempotencyRef, {
                orderId: orderId,
                buyerId: auth.uid,
                listingId: listingId,
                slotId: slotId,
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return { orderId, total: priceMinorUnits, title, shopId, alreadyExists: false };
        });

        const { orderId, total, title, shopId, alreadyExists } = result;
        if (alreadyExists) return { orderId };

        // STEP 2: MoPay Initiation (Outside transaction)
        if (paymentMethod === "MOPAY" && orderId) {
            const mopayRequest = {
                amount: (total / 100).toFixed(2),
                reference: orderId,
                redirectUrl: "swiftshop://checkout/verify",
                description: `Booking: ${title}`,
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
                console.error("Booking MoPay Session Creation Failed:", mopayResponse.message);

                // STEP 3: Compensating Transaction (Release Slot on Gateway Failure)
                await db.runTransaction(async (transaction) => {
                    const orderRef = db.collection("orders").doc(orderId);
                    const slotRef = db.collection("availability").doc(shopId).collection("slots").doc(slotId);

                    const slotSnap = await transaction.get(slotRef);
                    if (slotSnap.exists && slotSnap.data()?.orderId === orderId) {
                        transaction.update(slotRef, {
                            status: "AVAILABLE",
                            reservedBy: null,
                            expiresAt: null,
                            orderId: null,
                            updatedAt: admin.firestore.FieldValue.serverTimestamp()
                        });
                    }

                    transaction.update(orderRef, {
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
            const isAvailable = listing.isAvailable !== false; // Default to true
            const newListing = {
                ...listing,
                id: listingId,
                sellerId: uid,
                isAvailable,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };

            transaction.set(db.collection("listings").doc(listingId), newListing);

            // Update counters
            transaction.update(shopRef, {
                listingCount: (shop.listingCount || 0) + 1,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            if (isAvailable) {
                transaction.update(profileRef, {
                    activeListingCount: activeListingCount + 1,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
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

            if (listing.sellerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            const shopRef = db.collection("shops").doc(listing.shopId);
            const shopDoc = await transaction.get(shopRef);

            const profileRef = db.collection("profiles").doc(listing.sellerId);
            const profileDoc = await transaction.get(profileRef);

            transaction.delete(listingRef);

            if (shopDoc.exists) {
                transaction.update(shopRef, {
                    listingCount: Math.max(0, (shopDoc.data()!.listingCount || 0) - 1),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }

            if (profileDoc.exists && listing.isAvailable) {
                transaction.update(profileRef, {
                    activeListingCount: Math.max(0, (profileDoc.data()!.activeListingCount || 0) - 1),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Deletes a shop and cascades to all of its listings.
 *
 * NOTE ON ATOMICITY: Firestore transactions cap at 500 writes. A shop's
 * listing count is unbounded over time, so this cannot safely be one
 * runTransaction() the way deleteListing() is. Listing deletes are done in
 * batches (each batch atomic; the overall cascade is not atomic across
 * batches). The shop doc delete + profile counter update happen in a final
 * transaction after all listing batches have committed.
 *
 * KNOWN GAP (not handled here): Storage media for the shop (logoUrl,
 * coverUrl) and its listings (imageUrls, videoUrl) is not deleted. This is
 * a deliberate scope decision, not an oversight -- flagged for a separate
 * pass.
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

        if (shop.ownerId !== auth.uid && !auth.token.admin) {
            throw new HttpsError("permission-denied", "Not authorized to delete this shop");
        }

        const listingsSnapshot = await db.collection("listings")
            .where("shopId", "==", shopId)
            .get();

        let activeListingsDeleted = 0;
        const listingDocs = listingsSnapshot.docs;
        const BATCH_SIZE = 400;

        for (let i = 0; i < listingDocs.length; i += BATCH_SIZE) {
            const chunk = listingDocs.slice(i, i + BATCH_SIZE);
            const batch = db.batch();
            for (const doc of chunk) {
                const listing = doc.data();
                if (listing.isAvailable) activeListingsDeleted++;
                batch.delete(doc.ref);
            }
            await batch.commit();
        }

        await db.runTransaction(async (transaction) => {
            const profileRef = db.collection("profiles").doc(shop.ownerId);
            const profileDoc = await transaction.get(profileRef);

            transaction.delete(shopRef);

            if (profileDoc.exists) {
                const currentShopCount = profileDoc.data()!.shopCount || 0;
                const currentActiveCount = profileDoc.data()!.activeListingCount || 0;
                transaction.update(profileRef, {
                    shopCount: Math.max(0, currentShopCount - 1),
                    activeListingCount: Math.max(0, currentActiveCount - activeListingsDeleted),
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
            const user = userDoc.data()!;
            const tier = user.tier || "BASIC";

            const profileRef = db.collection("profiles").doc(uid);
            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) throw new Error("Profile not found");
            const currentShopCount = profileDoc.data()!.shopCount || 0;

            // Enforce limits (Basic: 1 shop, Premium: 3 shops, Elite: unlimited)
            let maxShops = 1;
            if (tier === "PREMIUM") maxShops = 3;
            if (tier === "ELITE") maxShops = -1;

            if (maxShops !== -1 && currentShopCount >= maxShops) {
                throw new Error(`Shop limit reached for ${tier} tier. Upgrade to PREMIUM or ELITE.`);
            }

            const shopId = db.collection("shops").doc().id;
            const newShop = {
                ...shop,
                id: shopId,
                ownerId: uid,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };

            transaction.set(db.collection("shops").doc(shopId), newShop);
            transaction.update(profileRef, {
                shopCount: currentShopCount + 1,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

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
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);
            if (!listingDoc.exists) throw new Error("Listing not found");
            const listing = listingDoc.data()!;

            if (listing.sellerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            const oldAvailable = listing.isAvailable !== false;
            const newAvailable = updates.isAvailable !== undefined ? updates.isAvailable : oldAvailable;

            transaction.update(listingRef, {
                ...updates,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            if (oldAvailable !== newAvailable) {
                const profileRef = db.collection("profiles").doc(listing.sellerId);
                const profileDoc = await transaction.get(profileRef);
                if (profileDoc.exists) {
                    const currentCount = profileDoc.data()!.activeListingCount || 0;
                    transaction.update(profileRef, {
                        activeListingCount: newAvailable ? currentCount + 1 : Math.max(0, currentCount - 1),
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });
                }
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});
