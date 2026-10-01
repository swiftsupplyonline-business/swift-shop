import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { calculatePurchaseTotal, createPurchaseOrder, cancelOrder, confirmDelivery } from '../commerce';

const testEnv = firebaseTest({ projectId: 'swift-shop-reconciled' });
if (admin.apps.length === 0) admin.initializeApp({ projectId: 'swift-shop-reconciled' });

describe('Canonical Purchase Authority', () => {
  const db = admin.firestore();

  const seedListing = async (id: string, overrides: Record<string, unknown> = {}) => {
    await db.collection('listings').doc(id).set({
      id, title: 'Canonical Item', sellerId: 'seller_canonical', shopId: 'shop_canonical',
      listingType: 'PRODUCT', priceMinorUnits: 1000, priceCurrency: 'LSL',
      stockQuantity: 5, reservedQuantity: 0, status: 'ACTIVE', ...overrides
    });
  };

  afterAll(() => testEnv.cleanup());

  test('calculatePurchaseTotal calculates authoritative total', async () => {
    await seedListing('purchase_total_valid');
    const result = await testEnv.wrap(calculatePurchaseTotal)({
      data: { items: [{ listingId: 'purchase_total_valid', quantity: 2 }] },
      auth: { uid: 'buyer_total', token: {} }
    } as any);
    expect(result.subtotalMinorUnits).toBe(2000);
    expect(result.platformFeeMinorUnits).toBe(30);
    expect(result.totalMinorUnits).toBe(2030);
  });

  test('calculatePurchaseTotal rejects missing listing', async () => {
    await expect(testEnv.wrap(calculatePurchaseTotal)({
      data: { items: [{ listingId: 'missing_listing', quantity: 1 }] },
      auth: { uid: 'buyer_missing', token: {} }
    } as any)).rejects.toThrow(/not found/i);
  });

  test('calculatePurchaseTotal rejects unavailable listing', async () => {
    await seedListing('purchase_total_paused', { status: 'PAUSED' });
    await expect(testEnv.wrap(calculatePurchaseTotal)({
      data: { items: [{ listingId: 'purchase_total_paused', quantity: 1 }] },
      auth: { uid: 'buyer_paused', token: {} }
    } as any)).rejects.toThrow();
  });

  test('createPurchaseOrder reserves inventory, confirms wallet payment, and is idempotent', async () => {
    await db.collection('shops').doc('shop_canonical').set({
      ownerId: 'seller_canonical', name: 'Canonical Shop', locationLat: -29.31, locationLng: 27.48
    });
    await seedListing('purchase_create_valid');
    await db.collection('wallets').doc('buyer_create').set({ availableBalanceMinorUnits: 5000 });

    const wrapped = testEnv.wrap(createPurchaseOrder);
    const data = {
      items: [{ listingId: 'purchase_create_valid', quantity: 2 }],
      paymentMethod: 'SWIFT_WALLET',
      idempotencyKey: 'canonical-idempotency-1'
    };

    const first = await wrapped({ data, auth: { uid: 'buyer_create', token: {} } } as any);
    const second = await wrapped({ data, auth: { uid: 'buyer_create', token: {} } } as any);

    expect(second.orderId).toBe(first.orderId);
    const order = (await db.collection('orders').doc(first.orderId).get()).data();
    expect(order?.status).toBe('CONFIRMED');
    expect(order?.paymentStatus).toBe('PAID');
    expect(order?.settlementStatus).toBe('ESCROW_HOLD');

    const listing = (await db.collection('listings').doc('purchase_create_valid').get()).data();
    expect(listing?.reservedQuantity).toBe(2);
  });

  test('cancelOrder releases a reservation', async () => {
    const orderId = 'order_normal_cancel';
    const listingId = 'listing_normal_cancel';
    const reservationId = 'reservation_normal_cancel';

    await db.collection('listings').doc(listingId).set({
      id: listingId, title: 'Normal Cancel Item', sellerId: 'seller_cancel',
      shopId: 'shop_cancel', priceMinorUnits: 1000, stockQuantity: 5,
      reservedQuantity: 2, isAvailable: true, status: 'ACTIVE', listingType: 'PRODUCT'
    });
    await db.collection('reservations').doc(reservationId).set({
      id: reservationId, orderId, listingId, quantity: 2, status: 'ACTIVE'
    });
    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_cancel', sellerId: 'seller_cancel', status: 'RESERVED'
    });

    const result = await testEnv.wrap(cancelOrder)({
      data: { orderId, reason: 'Customer changed mind' },
      auth: { uid: 'buyer_cancel', token: {}, rawToken: '' }
    } as any);

    expect(result.success).toBe(true);
    expect((await db.collection('orders').doc(orderId).get()).data()?.status).toBe('CANCELLED');
    expect((await db.collection('reservations').doc(reservationId).get()).data()?.status).toBe('RELEASED');
  });

  test('confirmDelivery self-pickup path settles escrow', async () => {
    const orderId = 'order_self_pickup_normal';
    await db.collection('wallets').doc('seller_pickup').set({ availableBalanceMinorUnits: 0 });
    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_pickup', sellerId: 'seller_pickup',
      status: 'READY', requiresDelivery: false, subtotalMinorUnits: 1000,
      deliveryFeeMinorUnits: 0, platformFeeMinorUnits: 15, totalMinorUnits: 1015,
      settlementStatus: 'ESCROW_HOLD'
    });

    const result = await testEnv.wrap(confirmDelivery)({
      data: { orderId }, auth: { uid: 'buyer_pickup', token: {}, rawToken: '' }
    } as any);

    expect(result.success).toBe(true);
    expect((await db.collection('orders').doc(orderId).get()).data()?.settlementStatus).toBe('SETTLED');
  });
});
