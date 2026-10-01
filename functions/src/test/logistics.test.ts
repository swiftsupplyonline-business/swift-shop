import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { updateDeliveryStatus } from '../fulfillment';

const testEnv = firebaseTest({ projectId: 'swift-shop-reconciled' });
if (admin.apps.length === 0) admin.initializeApp({ projectId: 'swift-shop-reconciled' });

describe('Canonical Fulfillment Authority', () => {
  const db = admin.firestore();

  afterAll(() => testEnv.cleanup());

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
