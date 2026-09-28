import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { updateDeliveryStatus } from '../logistics';

const testEnv = firebaseTest({
  projectId: 'swift-shop-reconciled',
});

describe('Logistics Authoritative Logic (SWIFT-019)', () => {
  let wrapped: any;
  const db = admin.firestore();

  beforeAll(async () => {
    wrapped = testEnv.wrap(updateDeliveryStatus);
  });

  afterAll(() => {
    testEnv.cleanup();
  });

  test('Claim Route - Authorized driver succeeds and becomes driverId', async () => {
    const providerId = 'merchant_123';
    const driverId = 'driver_456';
    const routeId = 'route_claim_success';

    // 1. Setup provider-driver relationship
    await db.collection('deliveryProviders').doc(providerId)
      .collection('drivers').doc(driverId).set({ authorized: true });

    // 2. Setup requested route
    await db.collection('deliveryRoutes').doc(routeId).set({
      id: routeId,
      providerId: providerId,
      driverId: '',
      status: 'REQUESTED'
    });

    // 3. Call as authorized driver
    await wrapped({
      data: { routeId, status: 'ASSIGNED' },
      auth: { uid: driverId, token: { role: 'DRIVER' } as any as any }
    });

    // 4. Verify
    const routeDoc = await db.collection('deliveryRoutes').doc(routeId).get();
    expect(routeDoc.data()?.status).toBe('ASSIGNED');
    expect(routeDoc.data()?.driverId).toBe(driverId);
  });

  test('Claim Route - Unauthorized driver is rejected', async () => {
    const providerId = 'merchant_789';
    const driverId = 'stranger_danger';
    const routeId = 'route_claim_fail';

    await db.collection('deliveryRoutes').doc(routeId).set({
      id: routeId,
      providerId: providerId,
      driverId: '',
      status: 'REQUESTED'
    });

    // Call as unauthorized driver
    await expect(wrapped({
      data: { routeId, status: 'ASSIGNED' },
      auth: { uid: driverId, token: { role: 'DRIVER' } as any }
    })).rejects.toThrow(/not an authorized driver/);
  });

  test('Delivery route - normal claimed-to-delivered path', async () => {
    const providerId = 'merchant_normal_path';
    const driverId = 'driver_normal_path';
    const routeId = 'route_normal_path';

    await db.collection('deliveryProviders').doc(providerId).collection('drivers').doc(driverId).set({ authorized: true });
    await db.collection('deliveryRoutes').doc(routeId).set({
      id: routeId, orderId: 'order_normal_path', buyerId: 'buyer_normal_path',
      sellerId: providerId, providerId, driverId: '', status: 'REQUESTED',
      pickupLat: -29.31, pickupLng: 27.48, dropoffLat: -29.30, dropoffLng: 27.49
    });

    const call = (status: string) => testEnv.wrap(updateDeliveryStatus)({
      data: { routeId, status },
      auth: { uid: driverId, token: { role: 'DRIVER' } as any }
    });

    await call('ASSIGNED');
    await call('AT_PICKUP');
    await call('PICKUP_CONFIRMED');
    await call('IN_TRANSIT');
    await call('DELIVERED');

    const route = (await db.collection('deliveryRoutes').doc(routeId).get()).data();
    expect(route?.status).toBe('DELIVERED');
    expect(route?.driverId).toBe(driverId);
  });
});
