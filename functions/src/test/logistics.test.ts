import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { createDeliveryJob, updateDeliveryStatus } from '../fulfillment';

const testEnv = firebaseTest({ projectId: 'swift-shop-reconciled' });
if (admin.apps.length === 0) admin.initializeApp({ projectId: 'swift-shop-reconciled' });

describe('Canonical Fulfillment Authority', () => {
  const db = admin.firestore();

  afterAll(() => testEnv.cleanup());

  test('createDeliveryJob converges concurrent calls on one deterministic route', async () => {
    const requestId = 'delivery_request_idempotent';
    const orderId = 'order_delivery_idempotent';
    const providerId = 'provider_delivery_idempotent';

    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_delivery_idempotent', sellerId: 'seller_delivery_idempotent',
      status: 'CONFIRMED', paymentStatus: 'PAID'
    });
    await db.collection('deliveryRequests').doc(requestId).set({
      id: requestId, requesterId: 'buyer_delivery_idempotent', relatedOrderId: orderId,
      merchantId: providerId, listingId: 'delivery_listing_idempotent',
      pickup: { lat: -29.31, lng: 27.48 }, dropoff: { lat: -29.32, lng: 27.49 },
      deliveryFeeMinorUnits: 2500, escrowStatus: 'HELD', status: 'ACCEPTED' // a priced delivery must be escrowed before a job exists
    });

    const wrapped = testEnv.wrap(createDeliveryJob);
    // Warm up container to avoid cold-start timeout in concurrency test
    try { await wrapped({ data: { requestId: 'warmup' }, auth: { uid: 'buyer', token: {} } } as any); } catch (e) {}

    const call = () => wrapped({
      data: { requestId },
      auth: { uid: 'buyer_delivery_idempotent', token: {} }
    } as any);

    const [first, second] = await Promise.all([call(), call()]);

    expect(first.routeId).toBe(second.routeId);
    expect(first.routeId).toBe(orderId);
    expect(first.alreadyExists || second.alreadyExists).toBe(true);

    const routes = await db.collection('deliveryRoutes')
      .where('orderId', '==', orderId)
      .get();
    expect(routes.size).toBe(1);
    expect(routes.docs[0].id).toBe(orderId);
  }, 60000); // two transactions contend for the same docs; the Firestore emulator resolves that by lock timeout/retry, which can take far longer than production

  test('authorized provider-scoped driver can claim a requested job', async () => {
    const providerId = 'provider_canonical_claim';
    const driverId = 'driver_canonical_claim';
    const routeId = 'route_canonical_claim';

    await db.collection('deliveryProviders').doc(providerId).collection('drivers').doc(driverId).set({ authorized: true });
    await db.collection('deliveryRoutes').doc(routeId).set({ id: routeId, providerId, driverId: '', status: 'REQUESTED' });

    await testEnv.wrap(updateDeliveryStatus)({
      data: { routeId, status: 'ASSIGNED' },
      auth: { uid: driverId, token: { role: 'DRIVER' } }
    } as any);

    const route = (await db.collection('deliveryRoutes').doc(routeId).get()).data();
    expect(route?.status).toBe('ASSIGNED');
    expect(route?.driverId).toBe(driverId);
  });

  test('unauthorized driver cannot claim provider job', async () => {
    const routeId = 'route_canonical_claim_denied';
    await db.collection('deliveryRoutes').doc(routeId).set({
      id: routeId, providerId: 'provider_only', driverId: '', status: 'REQUESTED'
    });

    await expect(testEnv.wrap(updateDeliveryStatus)({
      data: { routeId, status: 'ASSIGNED' },
      auth: { uid: 'unauthorized_driver', token: { role: 'DRIVER' } }
    } as any)).rejects.toThrow(/not authorized/i);
  });

  test('canonical lifecycle reaches delivered and rejects invalid transition', async () => {
    const providerId = 'provider_canonical_path';
    const driverId = 'driver_canonical_path';
    const routeId = 'route_canonical_path';
    const orderId = 'order_canonical_path';

    await db.collection('deliveryProviders').doc(providerId).collection('drivers').doc(driverId).set({ authorized: true });
    await db.collection('orders').doc(orderId).set({
      id: orderId, buyerId: 'buyer_canonical', sellerId: 'seller_canonical', status: 'CONFIRMED'
    });
    await db.collection('deliveryRoutes').doc(routeId).set({
      id: routeId, orderId, buyerId: 'buyer_canonical', sellerId: 'seller_canonical',
      providerId, driverId: '', status: 'REQUESTED'
    });

    const call = (status: string) => testEnv.wrap(updateDeliveryStatus)({
      data: { routeId, status },
      auth: { uid: driverId, token: { role: 'DRIVER' } }
    } as any);

    await call('ASSIGNED');
    await call('AT_PICKUP');
    await call('PICKUP_CONFIRMED');
    await call('IN_TRANSIT');
    await call('DELIVERED');

    const route = (await db.collection('deliveryRoutes').doc(routeId).get()).data();
    expect(route?.status).toBe('DELIVERED');
    await expect(call('IN_TRANSIT')).rejects.toThrow(/Cannot transition/i);
  });
});
