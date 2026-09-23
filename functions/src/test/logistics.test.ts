import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { updateDeliveryStatus } from '../logistics';

const testEnv = firebaseTest({
  projectId: 'demo-swift-shop-reconciled',
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
      auth: { uid: driverId, token: { role: 'DRIVER' } }
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
      auth: { uid: driverId, token: { role: 'DRIVER' } }
    })).rejects.toThrow(/not an authorized driver/);
  });
});
