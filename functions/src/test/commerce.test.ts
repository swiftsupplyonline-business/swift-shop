import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { createOrder, cancelOrder, confirmDelivery } from '../commerce';
import { MopayClient } from '../mopay';

const testEnv = firebaseTest({
  projectId: 'swift-shop-reconciled',
});

// Mock MopayClient
jest.mock('../mopay', () => ({
  MopayClient: {
    initiatePaymentSession: jest.fn()
  },
  MOPAY_API_KEY: { value: () => 'mock-key' }
}));

describe('Commerce Payment Concurrency (SWIFT-021)', () => {
  let wrapped: any;
  const db = admin.firestore();

  beforeAll(async () => {
    wrapped = testEnv.wrap(createOrder);
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  test('createOrder - Concurrent request detects active lease', async () => {
    const orderId = 'order_concurrency_test';
    const idempotencyKey = 'key_123';

    // 1. Setup existing order with a fresh "CREATING" lease
    await db.collection('orders').doc(orderId).set({
      id: orderId,
      totalMinorUnits: 1000,
      paymentSessionStatus: 'CREATING',
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    await db.collection('idempotencyKeys').doc(idempotencyKey).set({
      orderId: orderId,
      userId: 'user_1'
    });

    // 2. Call again immediately
    await expect(wrapped({
      data: {
        items: [{ listingId: 'l1', quantity: 1 }],
        requiresDelivery: false,
        paymentMethod: 'MOPAY',
        idempotencyKey: idempotencyKey
      },
      auth: { uid: 'user_1', token: { email: 'test@test.com' } }
    })).rejects.toThrow(/RETRY_TOO_SOON/);
  });

  test('createOrder - Recovery logic handles failed session', async () => {
    const orderId = 'order_recovery_test';
    const idempotencyKey = 'key_456';

    // 1. Setup failed order
    await db.collection('orders').doc(orderId).set({
      id: orderId,
      totalMinorUnits: 5000,
      paymentSessionStatus: 'FAILED',
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    await db.collection('idempotencyKeys').doc(idempotencyKey).set({
      orderId: orderId,
      userId: 'user_1'
    });

    // Setup mock success for recovery attempt
    (MopayClient.initiatePaymentSession as jest.Mock).mockResolvedValue({
      success: true,
      sessionId: 'session_recovered',
      paymentUrl: 'https://pay.me/recovered'
    });

    // 2. Call again (retry)
    const result = await wrapped({
      data: {
        items: [{ listingId: 'l1', quantity: 1 }],
        requiresDelivery: false,
        paymentMethod: 'MOPAY',
        idempotencyKey: idempotencyKey
      },
      auth: { uid: 'user_1', token: { email: 'test@test.com' } }
    });

    // 3. Verify recovery succeeded
    expect(result.mopaySessionId).toBe('session_recovered');
    const orderDoc = await db.collection('orders').doc(orderId).get();
    expect(orderDoc.data()?.paymentSessionStatus).toBe('CREATED');
  });

  test('createOrder - Amount authority: uses order total, not client request', async () => {
    const orderId = 'order_amount_authority';
    const idempotencyKey = 'key_amount_test';

    await db.collection('orders').doc(orderId).set({
      id: orderId,
      totalMinorUnits: 9999, // Authoritative M99.99
      paymentSessionStatus: 'FAILED',
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    await db.collection('idempotencyKeys').doc(idempotencyKey).set({
      orderId: orderId,
      userId: 'user_1'
    });

    (MopayClient.initiatePaymentSession as jest.Mock).mockClear();
    (MopayClient.initiatePaymentSession as jest.Mock).mockResolvedValue({
      success: true,
      sessionId: 's1',
      paymentUrl: 'u1'
    });

    await wrapped({
      data: {
        items: [{ listingId: 'l1', quantity: 1 }],
        requiresDelivery: false,
        paymentMethod: 'MOPAY',
        idempotencyKey: idempotencyKey,
        totalMinorUnits: 1 // Malicious client attempt to pay M0.01
      },
      auth: { uid: 'user_1', token: { email: 'test@test.com' } }
    });

    // Verify MoPay received the authoritative amount (99.99)
    expect(MopayClient.initiatePaymentSession).toHaveBeenCalledWith(expect.objectContaining({
      amount: 99.99
    }));
  });

  test('cancelOrder - normal pre-payment cancellation releases reservation', async () => {
    const orderId = 'order_normal_cancel';
    const listingId = 'listing_normal_cancel';
    const reservationId = 'reservation_normal_cancel';

    await db.collection('listings').doc(listingId).set({
      id: listingId, title: 'Normal Cancel Item', sellerId: 'seller_cancel',
      shopId: 'shop_cancel', priceMinorUnits: 1000, stockQuantity: 5,
      reservedQuantity: 2, isAvailable: true
    });
    await db.collection('reservations').doc(reservationId).set({
      id: reservationId, orderId, listingId, quantity: 2, status: 'ACTIVE'
    });
    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_cancel', sellerId: 'seller_cancel', status: 'RESERVED'
    });

    const result = await testEnv.wrap(cancelOrder)({
      data: { orderId, reason: 'Customer changed mind' },
      auth: { uid: 'buyer_cancel', token: {} as any }
    });

    expect(result.success).toBe(true);
    expect((await db.collection('orders').doc(orderId).get()).data()?.status).toBe('CANCELLED');
    expect((await db.collection('reservations').doc(reservationId).get()).data()?.status).toBe('RELEASED');
    const listing = (await db.collection('listings').doc(listingId).get()).data();
    expect(listing?.reservedQuantity).toBe(0);
    expect(listing?.stockQuantity).toBe(5);
  });

  test('confirmDelivery - self-pickup normal path settles from READY', async () => {
    const orderId = 'order_self_pickup_normal';
    await db.collection('wallets').doc('seller_pickup').set({ availableBalanceMinorUnits: 0 });
    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_pickup', sellerId: 'seller_pickup',
      status: 'READY', requiresDelivery: false, subtotalMinorUnits: 1000,
      deliveryFeeMinorUnits: 0, platformFeeMinorUnits: 15, totalMinorUnits: 1015,
      settlementStatus: 'ESCROW_HOLD'
    });

    const result = await testEnv.wrap(confirmDelivery)({
      data: { orderId }, auth: { uid: 'buyer_pickup', token: {} as any }
    });

    expect(result.success).toBe(true);
    const order = (await db.collection('orders').doc(orderId).get()).data();
    expect(order?.status).toBe('DELIVERED');
    expect(order?.settlementStatus).toBe('SETTLED');
    expect((await db.collection('wallets').doc('seller_pickup').get()).data()?.availableBalanceMinorUnits).toBe(1000);
  });

  test('confirmDelivery - delivery normal path settles from DELIVERED', async () => {
    const orderId = 'order_delivery_confirm_normal';
    await db.collection('wallets').doc('seller_delivery').set({ availableBalanceMinorUnits: 0 });
    await db.collection('wallets').doc('provider_delivery').set({ availableBalanceMinorUnits: 0 });
    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_delivery', sellerId: 'seller_delivery',
      deliveryProviderSellerId: 'provider_delivery', status: 'DELIVERED',
      requiresDelivery: true, subtotalMinorUnits: 2000, deliveryFeeMinorUnits: 500,
      platformFeeMinorUnits: 30, totalMinorUnits: 2530, settlementStatus: 'ESCROW_HOLD'
    });

    const result = await testEnv.wrap(confirmDelivery)({
      data: { orderId }, auth: { uid: 'buyer_delivery', token: {} as any }
    });

    expect(result.success).toBe(true);
    expect((await db.collection('orders').doc(orderId).get()).data()?.settlementStatus).toBe('SETTLED');
    expect((await db.collection('wallets').doc('seller_delivery').get()).data()?.availableBalanceMinorUnits).toBe(2000);
    expect((await db.collection('wallets').doc('provider_delivery').get()).data()?.availableBalanceMinorUnits).toBe(500);
  });
});
