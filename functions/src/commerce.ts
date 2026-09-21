import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { MopayClient, MOPAY_API_KEY } from "./mopay";
import { resolveEntitlement } from "./entitlements";

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

    const { items, requiresDelivery, deliveryAddress, selectedDeliveryListingId } = request.data;
    if (!items || !Array.isArray(items)) throw new HttpsError("invalid-argument", "Missing items");
    if (typeof requiresDelivery !== "boolean") throw new HttpsError("invalid-argument", "requiresDelivery is required");
    if (requiresDelivery && !deliveryAddress) throw new HttpsError("invalid-argument", "deliveryAddress is required when requiresDelivery is true");

    let subtotal = 0;
    let shopId = "";
    const db = admin.firestore();

    for (const item of items) {
        const listingDoc = await db.collection("listings").doc(item.listingId).get();
        if (!listingDoc.exists) {
            const itemTitle = item.title || "Unknown Item";
            throw new HttpsError("not-found", `Item "${itemTitle}" is no longer available. Please remove it from your cart.`);
        }
        const listing = listingDoc.data()!;
        subtotal += (listing.priceMinorUnits || 0) * (item.quantity || 1);

        if (!shopId) {
            shopId = listing.shopId;
        } else if (listing.shopId !== shopId) {
            throw new HttpsError("invalid-argument", "Multi-shop orders are not supported in this version.");
        }
    }

    let deliveryFee = 0;
    if (requiresDelivery) {
        if (selectedDeliveryListingId) {
            const deliveryListingDoc = await db.collection("listings").doc(selectedDeliveryListingId).get();
            if (!deliveryListingDoc.exists) throw new HttpsError("not-found", "Delivery listing not found");
            const deliveryListing = deliveryListingDoc.data()!;
            if (deliveryListing.listingType !== "DELIVER") throw new HttpsError("failed-precondition", "Invalid delivery listing type");
            if (!deliveryListing.isAvailable) throw new HttpsError("failed-precondition", "Delivery service is currently unavailable");
            // Delivery provider may belong to a different shop than the merchant ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â cross-shop is valid.
            deliveryFee = deliveryListing.priceMinorUnits || 0;
        } else {
            // No listing selected Ã¢â‚¬â€ fee is 0; authoritative fee comes from the accepted request.
            deliveryFee = 0;
        }
    }











    const platformFee = Math.floor((subtotal * 15) / 1000);
    const total = subtotal + deliveryFee + platformFee;

    return {
        subtotalMinorUnits: subtotal,
        deliveryFeeMinorUnits: deliveryFee,
        platformFeeMinorUnits: platformFee,
        totalMinorUnits: total,
        currency: "LSL"
    };
});

export const createOrder = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { items, requiresDelivery, deliveryAddress, paymentMethod, provider, idempotencyKey, selectedDeliveryListingId, deliveryRequestId } = request.data;
    if (!items || !Array.isArray(items) || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing items or idempotencyKey");
    }
    if (typeof requiresDelivery !== "boolean") throw new HttpsError("invalid-argument", "requiresDelivery is required");
    if (requiresDelivery && !deliveryAddress) throw new HttpsError("invalid-argument", "deliveryAddress is required when requiresDelivery is true");
    if (requiresDelivery && !deliveryRequestId) throw new HttpsError("invalid-argument", "deliveryRequestId is required for orders requiring delivery");

    const db = admin.firestore();
    let orderId = "";
    let finalTotal = 0;
    let existingPayment: { paymentUrl?: string, mopaySessionId?: string } | null = null;

    try {
        const transactionResult = await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                const existingId = idempotencyDoc.data()?.orderId;
                const orderSnap = await transaction.get(db.collection("orders").doc(existingId));
                const orderData = orderSnap.data();

                // SWIFT-021: Check session lifecycle
                const sessionStatus = orderData?.paymentSessionStatus || "FAILED";
                const isCreated = sessionStatus === "CREATED";
                const isCreating = sessionStatus === "CREATING";
                const createdAt = orderData?.updatedAt?.toMillis() || 0;
                const timedOut = isCreating && (Date.now() - createdAt > 5 * 60 * 1000);

                if (isCreated) {
                    console.log(`Idempotent request: Session already exists for ${existingId}`);
                    return {
                        orderId: existingId,
                        total: orderData?.totalMinorUnits || 0,
                        paymentUrl: orderData?.paymentUrl,
                        mopaySessionId: orderData?.mopaySessionId,
                        isIdempotent: true,
                        recoveryNeeded: false
                    };
                }

                if (isCreating && !timedOut) {
                    throw new Error("Payment session is currently being created by another request.");
                }

                // Recovery needed
                console.log(`Recovery needed for order ${existingId} (status: ${sessionStatus})`);
                transaction.update(db.collection("orders").doc(existingId), {
                    paymentSessionStatus: "CREATING",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                return {
                    orderId: existingId,
                    total: orderData?.totalMinorUnits || 0,
                    isIdempotent: true,
                    recoveryNeeded: true
                };
            }

            let subtotal = 0;
            const newOrderId = db.collection("orders").doc().id;
            const validatedItems = [];
            let shopId = "";
            let sellerId = "";

            // 1. Validate Delivery Request if applicable
            let deliveryFee = 0;
            let finalDeliveryListingId = selectedDeliveryListingId;
            // deliveryProviderSellerId is the uid of the seller who owns the delivery listing.
            // This may differ from the product merchant ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â cross-shop delivery is valid.
            let deliveryProviderSellerId = "";

            if (requiresDelivery) {
                const drRef = db.collection("deliveryRequests").doc(deliveryRequestId);
                const drDoc = await transaction.get(drRef);
                if (!drDoc.exists) throw new Error("Delivery request not found");
                const drData = drDoc.data()!;
                if (drData.status !== "ACCEPTED") throw new Error(`Delivery request status is ${drData.status}. Must be ACCEPTED.`);
                if (drData.requesterId !== auth.uid) throw new Error("Delivery request ownership mismatch");

                // Capture delivery fee from the accepted request snapshot (not current listing price).
                deliveryFee = drData.deliveryFeeMinorUnits || 0;
                finalDeliveryListingId = drData.listingId;
                // The merchant who accepted the delivery request is the delivery provider.
                // This is authoritative ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â set by createDeliveryRequest from listing.sellerId.
                deliveryProviderSellerId = drData.merchantId || "";
            }

            // 2. Validate Items and Reserve Inventory
            const now = admin.firestore.Timestamp.now();
            const reservationExpiresAt = admin.firestore.Timestamp.fromMillis(now.toMillis() + 15 * 60 * 1000);

            for (const item of items) {
                const listingRef = db.collection("listings").doc(item.listingId);
                const listingDoc = await transaction.get(listingRef);

                if (!listingDoc.exists) {
                    const itemTitle = item.title || "Unknown Item";
                    throw new Error(`Item "${itemTitle}" is no longer available. Please remove it from your cart.`);
                }
                const listing = listingDoc.data()!;

                if (!listing.isAvailable) throw new Error(`Listing ${item.listingId} is not available`);

                const requestedQty = item.quantity || 1;
                const totalStock = listing.stockQuantity || 0;
                const currentReserved = listing.reservedQuantity || 0;
                const available = totalStock - currentReserved;

                if (available < requestedQty) {
                    throw new Error(`Insufficient stock for ${listing.title}. Requested: ${requestedQty}, Available: ${available}`);
                }

                // Reserve Stock
                transaction.update(listingRef, {
                    reservedQuantity: currentReserved + requestedQty,
                    updatedAt: now
                });

                // Create Reservation Record
                const resId = db.collection("reservations").doc().id;
                transaction.set(db.collection("reservations").doc(resId), {
                    id: resId,
                    orderId: newOrderId,
                    listingId: item.listingId,
                    quantity: requestedQty,
                    status: "ACTIVE",
                    expiresAt: reservationExpiresAt,
                    createdAt: now
                });

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

            const platformFee = Math.floor((subtotal * 15) / 1000);
            const total = subtotal + deliveryFee + platformFee;
            finalTotal = total;

            let orderStatus = "RESERVED";

            if (paymentMethod === "SWIFT_WALLET") {
                const walletRef = db.collection("wallets").doc(auth.uid);
                const walletDoc = await transaction.get(walletRef);
                if (!walletDoc.exists) throw new Error("Wallet not found");

                const availableBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                if (availableBalance < total) {
                    throw new Error(`Insufficient wallet balance. Required: ${total}, Available: ${availableBalance}`);
                }

                transaction.update(walletRef, {
                    availableBalanceMinorUnits: availableBalance - total,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    debitAccount: `user_${auth.uid}`,
                    creditAccount: "system_order_escrow",
                    amountMinorUnits: total,
                    currency: "LSL",
                    reference: `ORDER_PAY_${newOrderId}`,
                    timestamp: admin.firestore.FieldValue.serverTimestamp()
                });

                orderStatus = "CONFIRMED";
            }

            const orderDoc = {
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
                inventoryStatus: "RESERVED",
                settlementStatus: paymentMethod === "SWIFT_WALLET" ? "ESCROW_HOLD" : "PENDING",
                paymentStatus: paymentMethod === "SWIFT_WALLET" ? "PAID" : "PENDING",
                requiresDelivery: requiresDelivery,
                deliveryRequestId: deliveryRequestId || null,
                deliveryProviderSellerId: deliveryProviderSellerId || null,
                selectedDeliveryListingId: finalDeliveryListingId || null,
                deliveryAddress: deliveryAddress || {},
                paymentMethod: paymentMethod || "MOPAY",
                provider: provider || null,
                idempotencyKey: idempotencyKey,
                paymentSessionStatus: paymentMethod === "MOPAY" ? "CREATING" : "NA",
                reservationExpiresAt: reservationExpiresAt,
                createdAt: now,
                updatedAt: now
            };

            transaction.set(db.collection("orders").doc(newOrderId), orderDoc);
            transaction.set(idempotencyRef, {
                orderId: newOrderId,
                userId: auth.uid,
                createdAt: now
            });

            return { orderId: newOrderId, total: total, isIdempotent: false };
        });

        orderId = transactionResult.orderId;
        finalTotal = transactionResult.total;
        if (transactionResult.isIdempotent) {
            existingPayment = {
                paymentUrl: transactionResult.paymentUrl,
                mopaySessionId: transactionResult.mopaySessionId
            };
            if (!transactionResult.recoveryNeeded && !existingPayment.paymentUrl) {
               // This shouldn't happen with the new logic, but safety first.
               return { orderId };
            }
        }

        if (paymentMethod === "MOPAY" && orderId) {
            // SWIFT-021: Recovery/Creation logic
            if (existingPayment?.paymentUrl && !transactionResult.recoveryNeeded) {
                return {
                    orderId,
                    paymentUrl: existingPayment.paymentUrl,
                    mopaySessionId: existingPayment.mopaySessionId
                };
            }
            const mopayRequest = {
                amount: finalTotal / 100,
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
                    paymentSessionStatus: "CREATED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                return {
                    orderId,
                    paymentUrl: mopayResponse.paymentUrl,
                    mopaySessionId: mopayResponse.sessionId
                };
            } else {
                console.error("MoPay Session Creation Failed:", mopayResponse.message);
                await db.collection("orders").doc(orderId).update({
                    paymentSessionStatus: "FAILED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
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

        // 2. Authoritative Gateway Verification
        const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
        if (!mopaySession) throw new Error("Could not verify session with MoPay.");

        // 3. Validation
        if (mopaySession.reference !== order.id) {
            throw new Error("Session reference mismatch.");
        }

        const mopayAmountMinor = Math.round(mopaySession.amount * 100);
        if (mopayAmountMinor !== order.totalMinorUnits) {
            throw new Error(`Amount mismatch. Expected: ${order.totalMinorUnits}, Got: ${mopayAmountMinor}`);
        }

        // 4. Atomic Transition (only if SUCCESS)
        if (mopaySession.transactionStatus === "SUCCESS") {
            await db.runTransaction(async (transaction) => {
                const freshOrderDoc = await transaction.get(orderDoc.ref);
                const freshOrder = freshOrderDoc.data()!;

                if (freshOrder.status === "CONFIRMED") return; // Idempotency: already confirmed, nothing to do
                if (freshOrder.status !== "RESERVED") throw new Error(`Order is in state ${freshOrder.status}, cannot confirm.`);

                const now = admin.firestore.Timestamp.now();

                // COMMIT INVENTORY
                const resQuery = db.collection("reservations").where("orderId", "==", order.id);
                const resSnap = await transaction.get(resQuery);

                if (resSnap.empty) throw new Error("No reservations found for this order.");

                for (const resDoc of resSnap.docs) {
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") {
                        throw new Error(`Reservation for ${reservation.listingId} is ${reservation.status}. Cannot commit.`);
                    }

                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = await transaction.get(listingRef);

                    if (listingSnap.exists) {
                        const listing = listingSnap.data()!;
                        transaction.update(listingRef, {
                            stockQuantity: (listing.stockQuantity || 0) - reservation.quantity,
                            reservedQuantity: Math.max(0, (listing.reservedQuantity || 0) - reservation.quantity),
                            updatedAt: now
                        });
                    }
                    transaction.update(resDoc.ref, { status: "COMMITTED", updatedAt: now });
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
                    timestamp: now
                });

                transaction.update(orderDoc.ref, {
                    status: "CONFIRMED",
                    paymentStatus: "SUCCESS",
                    inventoryStatus: "COMMITTED",
                    settlementStatus: "ESCROW_HOLD",
                    gatewayTransactionId: mopaySession.transactionId,
                    updatedAt: now
                });
            });

            return { status: "SUCCESS", orderId: order.id };
        } else {
             // Handle terminal failure (release reservation)
             if (mopaySession.transactionStatus === "FAILED" || mopaySession.transactionStatus === "CANCELLED") {
                 await db.runTransaction(async (transaction) => {
                     const freshOrderDoc = await transaction.get(orderDoc.ref);
                     const freshOrder = freshOrderDoc.data()!;
                     if (freshOrder.status !== "RESERVED") return;

                     const now = admin.firestore.Timestamp.now();
                     const resQuery = db.collection("reservations").where("orderId", "==", order.id);
                     const resSnap = await transaction.get(resQuery);

                     for (const resDoc of resSnap.docs) {
                         const reservation = resDoc.data();
                         if (reservation.status !== "ACTIVE") continue;

                         const listingRef = db.collection("listings").doc(reservation.listingId);
                         const listingSnap = await transaction.get(listingRef);
                         if (listingSnap.exists) {
                             const listing = listingSnap.data()!;
                             transaction.update(listingRef, {
                                 reservedQuantity: Math.max(0, (listing.reservedQuantity || 0) - reservation.quantity),
                                 updatedAt: now
                             });
                         }
                         transaction.update(resDoc.ref, { status: "RELEASED", updatedAt: now });
                     }
                     transaction.update(orderDoc.ref, {
                         status: mopaySession.transactionStatus,
                         paymentStatus: mopaySession.transactionStatus,
                         inventoryStatus: "RELEASED",
                         updatedAt: now
                     });
                 });
             } else {
                await orderDoc.ref.update({
                    paymentStatus: mopaySession.transactionStatus,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
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
 * Buyer confirms delivery of an order.
 * This triggers authoritative settlement (escrow release).
 */
export const confirmDelivery = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId } = request.data;
    if (!orderId) throw new HttpsError("invalid-argument", "Missing orderId");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.buyerId !== auth.uid) throw new Error("Unauthorized");
            if (order.status !== "READY" && order.status !== "DISPATCHED" && order.status !== "DELIVERED") {
                 throw new Error(`Order cannot be confirmed in state ${order.status}`);
            }

            if (order.settlementStatus === "SETTLED") return; // Idempotent

            const now = admin.firestore.Timestamp.now();

            // SETTLEMENT LOGIC
            const subtotal = order.subtotalMinorUnits || 0;
            const deliveryFee = order.deliveryFeeMinorUnits || 0;
            const platformFee = order.platformFeeMinorUnits || 0;
            const total = order.totalMinorUnits || 0;

            // The delivery provider may be a different seller from the product merchant.
            // deliveryProviderSellerId is set at order-creation time from the accepted
            // delivery request's merchantId. Fall back to sellerId for non-delivery orders
            // or legacy orders created before this field existed.
            const deliveryProviderSellerId: string =
                order.deliveryProviderSellerId || order.sellerId;

            // Debit Escrow
            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId,
                debitAccount: "system_order_escrow",
                creditAccount: "system_clearing",
                amountMinorUnits: total,
                currency: "LSL",
                reference: `SETTLE_ORDER_${orderId}`,
                timestamp: now
            });

            // Credit Product Seller Wallet (subtotal only)
            const sellerWalletRef = db.collection("wallets").doc(order.sellerId);
            const sellerWalletDoc = await transaction.get(sellerWalletRef);
            const currentSellerBalance = sellerWalletDoc.data()?.availableBalanceMinorUnits || 0;

            transaction.update(sellerWalletRef, {
                availableBalanceMinorUnits: currentSellerBalance + subtotal,
                updatedAt: now
            });

            const sellerLedgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(sellerLedgerId), {
                id: sellerLedgerId,
                debitAccount: "system_clearing",
                creditAccount: `user_${order.sellerId}`,
                amountMinorUnits: subtotal,
                currency: "LSL",
                reference: `SALE_PROCEEDS_${orderId}`,
                timestamp: now
            });

            // Credit Delivery Provider Wallet (delivery fee)
            // When the delivery provider is the same as the product seller, we credit
            // them separately so the ledger entries remain auditable regardless.
            if (deliveryFee > 0) {
                const providerWalletRef = db.collection("wallets").doc(deliveryProviderSellerId);
                const providerWalletDoc = await transaction.get(providerWalletRef);
                const currentProviderBalance = providerWalletDoc.data()?.availableBalanceMinorUnits || 0;

                transaction.update(providerWalletRef, {
                    availableBalanceMinorUnits: currentProviderBalance + deliveryFee,
                    updatedAt: now
                });

                const providerLedgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(providerLedgerId), {
                    id: providerLedgerId,
                    debitAccount: "system_clearing",
                    creditAccount: `user_${deliveryProviderSellerId}`,
                    amountMinorUnits: deliveryFee,
                    currency: "LSL",
                    reference: `DELIVERY_FEE_${orderId}`,
                    timestamp: now
                });
            }

            // Credit Platform Fees
            if (platformFee > 0) {
                const feeLedgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(feeLedgerId), {
                    id: feeLedgerId,
                    debitAccount: "system_clearing",
                    creditAccount: "system_fees",
                    amountMinorUnits: platformFee,
                    currency: "LSL",
                    reference: `PLATFORM_FEE_${orderId}`,
                    timestamp: now
                });
            }

            transaction.update(orderRef, {
                status: "DELIVERED",
                settlementStatus: "SETTLED",
                completedAt: now,
                updatedAt: now
            });
        });

        return { success: true };
    } catch (error: any) {
        console.error("Delivery confirmation failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Updates order status authoritative server-side.
 */
const SELLER_TRANSITIONS: Record<string, string[]> = {
    CONFIRMED: ["PROCESSING"],
    PROCESSING: ["READY"]
};

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
                // Buyer can only cancel PENDING or RESERVED orders.
                // New orders created under the reservation model use status RESERVED.
                if (!['PENDING', 'RESERVED'].includes(order.status)) throw new Error('Buyer can only cancel pending or reserved orders');
            } else if (isSeller) {
                // Seller transition matrix enforcement
                const allowedNext = SELLER_TRANSITIONS[order.status] || [];
                if (!allowedNext.includes(status)) {
                    throw new Error(`Seller cannot transition order from ${order.status} to ${status}`);
                }
            } else if (isAdmin) {
                // Admin has broad authority (preserved)
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

            // P0 #3: Release reservations for RESERVED orders only.
            // createOrder increments reservedQuantity ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â it does NOT decrement stockQuantity.
            // Therefore cancellation must mirror the payment-failure release path:
            // decrement reservedQuantity, mark reservation RELEASED, never touch stockQuantity.
            if (order.status === 'RESERVED') {
                const cancelResQuery = db.collection('reservations').where('orderId', '==', orderId);
                const cancelResSnap = await transaction.get(cancelResQuery);
                const cancelNow = admin.firestore.FieldValue.serverTimestamp();
                for (const resDoc of cancelResSnap.docs) {
                    const reservation = resDoc.data();
                    if (reservation.status !== 'ACTIVE') continue; // idempotency: skip already-released
                    const listingRef = db.collection('listings').doc(reservation.listingId);
                    const listingSnap = await transaction.get(listingRef);
                    if (listingSnap.exists) {
                        const listing = listingSnap.data()!;
                        transaction.update(listingRef, {
                            reservedQuantity: Math.max(0, (listing.reservedQuantity || 0) - reservation.quantity),
                            updatedAt: cancelNow
                        });
                    }
                    transaction.update(resDoc.ref, { status: 'RELEASED', updatedAt: cancelNow });
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

            const userDoc = await transaction.get(db.collection("users").doc(uid));
            if (!userDoc.exists) throw new Error("User not found");
            const user = userDoc.data()!;
            const tier = user.tier || "BASIC";

            const profileRef = db.collection("profiles").doc(uid);
            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) throw new Error("Profile not found");
            const activeListingCount = profileDoc.data()!.activeListingCount || 0;

            // --- Authoritative Quota Check ---
            const entitlement = resolveEntitlement(tier);

            // 1. Per-shop limit check (e.g. BASIC: 10 per shop)
            if (entitlement.includedListingsPerShop !== -1) {
                const shopListingsQuery = db.collection("listings")
                    .where("shopId", "==", listing.shopId)
                    .where("sellerId", "==", uid);
                const shopListingsSnapshot = await transaction.get(shopListingsQuery);
                if (shopListingsSnapshot.size >= entitlement.includedListingsPerShop) {
                    throw new Error("ADDITIONAL_FEE_REQUIRED");
                }
            }

            // 2. Total account limit check (e.g. PREMIUM: 50 total)
            if (entitlement.totalIncludedListings !== -1) {
                const allListingsQuery = db.collection("listings").where("sellerId", "==", uid);
                const allListingsSnapshot = await transaction.get(allListingsQuery);
                if (allListingsSnapshot.size >= entitlement.totalIncludedListings) {
                    throw new Error("ADDITIONAL_FEE_REQUIRED");
                }
            }
            // -----------------------------------

            const listingId = db.collection("listings").doc().id;
            const isAvailable = listing.isAvailable !== false; // Default to true

            const clientOwnedFields: Record<string, any> = {};
            const allowedKeys = [
                "shopId", "title", "description", "category", "tags",
                "priceMinorUnits", "priceCurrency", "imageUrls", "images", "videoUrl",
                "listingType", "isAvailable", "stockQuantity", "deliveryEstimateDays",
                "customFields", "durationMinutes", "fulfillmentOptions"
            ];
            for (const key of allowedKeys) {
                if (listing && listing[key] !== undefined) {
                    clientOwnedFields[key] = listing[key];
                }
            }

            const newListing = {
                ...clientOwnedFields,
                id: listingId,
                sellerId: uid,
                isAvailable,
                isSponsored: false,
                commitmentCount: 0,
                likeCount: 0,
                commentCount: 0,
                rankingScore: 0,
                title_lowercase: clientOwnedFields.title?.toLowerCase() || "",
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

            // --- Authoritative Entitlement Check ---
            const entitlement = resolveEntitlement(tier);

            const shopsQuery = db.collection("shops").where("ownerId", "==", uid);
            const shopsSnapshot = await transaction.get(shopsQuery);
            const realShopCount = shopsSnapshot.size;

            if (entitlement.maxShops !== -1 && realShopCount >= entitlement.maxShops) {
                throw new Error(`Shop limit reached for ${tier} tier. Max: ${entitlement.maxShops}`);
            }
            // ----------------------------------------

            const shopId = db.collection("shops").doc().id;
            const newShop = {
                ...shop,
                id: shopId,
                ownerId: uid,
                name_lowercase: shop.name?.toLowerCase() || "",
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
 * Authoritative shop update.
 */
export const updateShop = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { shopId, updates } = request.data;
    if (!shopId) throw new HttpsError("invalid-argument", "shopId required");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const shopRef = db.collection("shops").doc(shopId);
            const shopDoc = await transaction.get(shopRef);
            if (!shopDoc.exists) throw new Error("Shop not found");
            const shop = shopDoc.data()!;

            if (shop.ownerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            const allowedUpdateKeys = [
                "name", "description", "category", "logoUrl", "coverUrl",
                "locationLat", "locationLng", "locationAddress", "isActive"
            ];
            const filteredUpdates: Record<string, any> = {};
            for (const key of allowedUpdateKeys) {
                if (updates && updates[key] !== undefined) {
                    filteredUpdates[key] = updates[key];
                }
            }

            if (filteredUpdates.name) {
                filteredUpdates.name_lowercase = filteredUpdates.name.toLowerCase();
            }

            transaction.update(shopRef, {
                ...filteredUpdates,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
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

            const allowedUpdateKeys = [
                "shopId", "title", "description", "category", "tags",
                "priceMinorUnits", "priceCurrency", "imageUrls", "images", "videoUrl",
                "listingType", "isAvailable", "stockQuantity", "deliveryEstimateDays",
                "customFields", "durationMinutes", "fulfillmentOptions"
            ];
            const filteredUpdates: Record<string, any> = {};
            for (const key of allowedUpdateKeys) {
                if (updates && updates[key] !== undefined) {
                    filteredUpdates[key] = updates[key];
                }
            }

            if (filteredUpdates.shopId !== undefined && filteredUpdates.shopId !== listing.shopId) {
                const shopRef = db.collection("shops").doc(filteredUpdates.shopId);
                const shopDoc = await transaction.get(shopRef);
                if (!shopDoc.exists) throw new Error("Target shop not found");
                if (shopDoc.data()!.ownerId !== auth.uid) throw new Error("Unauthorized shop transfer");
            }

            const oldAvailable = listing.isAvailable !== false;
            const newAvailable = filteredUpdates.isAvailable !== undefined ? filteredUpdates.isAvailable : oldAvailable;

            if (filteredUpdates.title) {
                filteredUpdates.title_lowercase = filteredUpdates.title.toLowerCase();
            }

            transaction.update(listingRef, {
                ...filteredUpdates,
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

/**
 * Transactional, server-authoritative creation of a Listing comment.
 */
export const createListingComment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId, text, id: commentId } = request.data;
    if (!listingId || !text) throw new HttpsError("invalid-argument", "Missing listingId or text");

    const db = admin.firestore();
    const uid = auth.uid;

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const commentRef = db.collection("comments").doc(commentId || db.collection("comments").doc().id);

            const [listingDoc, commentDoc] = await Promise.all([
                transaction.get(listingRef),
                transaction.get(commentRef)
            ]);

            if (!listingDoc.exists) throw new Error("Listing not found");
            if (commentDoc.exists) return; // Idempotent

            const now = admin.firestore.FieldValue.serverTimestamp();

            // Server-authoritative comment document
            transaction.set(commentRef, {
                id: commentRef.id,
                listingId,
                authorId: uid,
                authorName: request.data.authorName || "Anonymous",
                authorAvatarUrl: request.data.authorAvatarUrl || "",
                text,
                parentCommentId: request.data.parentCommentId || "",
                replyCount: 0,
                likeCount: 0,
                createdAt: now,
                updatedAt: now
            });

            transaction.update(listingRef, {
                commentCount: admin.firestore.FieldValue.increment(1),
                updatedAt: now
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Transactional, server-authoritative deletion of a Listing comment.
 */
export const deleteListingComment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { commentId } = request.data;
    if (!commentId) throw new HttpsError("invalid-argument", "Missing commentId");

    const db = admin.firestore();
    const uid = auth.uid;

    try {
        await db.runTransaction(async (transaction) => {
            const commentRef = db.collection("comments").doc(commentId);
            const commentDoc = await transaction.get(commentRef);

            if (!commentDoc.exists) return; // Idempotent

            const commentData = commentDoc.data()!;
            if (commentData.authorId !== uid && auth.token.admin !== true) {
                throw new Error("Unauthorized");
            }

            const listingId = commentData.listingId;
            if (!listingId) throw new Error("Comment is not associated with a Listing");

            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);

            transaction.delete(commentRef);

            if (listingDoc.exists) {
                const currentCount = listingDoc.data()?.commentCount || 0;
                transaction.update(listingRef, {
                    commentCount: Math.max(0, currentCount - 1),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});
