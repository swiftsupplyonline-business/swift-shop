import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { createOrder } from '../commerce';
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
});
