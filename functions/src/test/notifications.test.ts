import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { notifyOnMessage } from '../notifications';

const testEnv = firebaseTest({
  projectId: 'swift-shop-reconciled',
});

// Mock admin.messaging
const mockSend = jest.fn();
jest.mock('firebase-admin', () => {
  const actual = jest.requireActual('firebase-admin');
  return {
    ...actual,
    messaging: () => ({
      sendEachForMulticast: mockSend
    })
  };
});

describe('Notifications Accounting (SWIFT-022)', () => {
  const db = admin.firestore();

  afterAll(() => {
    testEnv.cleanup();
  });

  test('notifyOnMessage - Multi-device accounting correctly handles partial failure', async () => {
    const userId = 'user_target';
    const conversationId = 'conv_123';
    const eventId = 'event_456';

    // 1. Setup target user with multiple devices
    const userRef = db.collection('users').doc(userId);
    await userRef.collection('devices').doc('device_ok').set({ token: 't1', isActive: true });
    await userRef.collection('devices').doc('device_bad').set({ token: 't2', isActive: true });

    // 2. Setup conversation
    await db.collection('conversations').doc(conversationId).set({
      participantIds: ['sender_uid', userId]
    });

    // 3. Mock partial failure (d1 succeeds, d2 fails permanent)
    mockSend.mockResolvedValue({
      successCount: 1,
      failureCount: 1,
      responses: [
        { success: true, messageId: 'm1' },
        { success: false, error: { code: 'messaging/registration-token-not-registered', message: 'unregistered' } }
      ]
    });

    // 4. Trigger function
    const wrapped = testEnv.wrap(notifyOnMessage);
    const snap = testEnv.firestore.makeDocumentSnapshot({
      conversationId: conversationId,
      senderId: 'sender_uid',
      text: 'Hello!'
    }, 'messages/msg_123');

    await wrapped({
       data: () => ({ ...snap.data() }),
       id: eventId
    } as any);

    // 5. Verify accounting
    const eventDoc = await db.collection('notificationEvents').doc(`${eventId}_${userId}`).get();
    const accounting = eventDoc.data()?.deviceAccounting;

    expect(accounting['device_ok'].status).toBe('SENT');
    expect(accounting['device_bad'].status).toBe('FAILED_PERMANENT');

    // Parent status should be SENT because all devices are accounted for (terminal states)
    expect(eventDoc.data()?.status).toBe('SENT');

    // device_bad should be deactivated
    const deviceBadDoc = await userRef.collection('devices').doc('device_bad').get();
    expect(deviceBadDoc.data()?.isActive).toBe(false);
  });

  test('notifyOnMessage - Correctly fan-out to multiple recipients in group chat', async () => {
    const conversationId = 'group_conv_123';
    const eventId = 'group_event_789';

    // 1. Setup conversation with 3 participants
    await db.collection('conversations').doc(conversationId).set({
      participantIds: ['sender_uid', 'user_1', 'user_2']
    });

    // 2. Setup recipients with devices
    await db.collection('users').doc('user_1').collection('devices').doc('d1').set({ token: 't1', isActive: true });
    await db.collection('users').doc('user_2').collection('devices').doc('d2').set({ token: 't2', isActive: true });

    mockSend.mockResolvedValue({ successCount: 1, failureCount: 0, responses: [{ success: true }] });

    // 3. Trigger function
    const { notifyOnMessage } = require('../notifications');
    const wrapped = testEnv.wrap(notifyOnMessage);
    const snap = testEnv.firestore.makeDocumentSnapshot({
      conversationId: conversationId,
      senderId: 'sender_uid',
      text: 'Group message'
    }, 'messages/group_msg_1');

    await wrapped({
       data: () => ({ ...snap.data() }),
       id: eventId
    } as any);

    // 4. Verify both recipients have logical event records
    const event1 = await db.collection('notificationEvents').doc(`${eventId}_user_1`).get();
    const event2 = await db.collection('notificationEvents').doc(`${eventId}_user_2`).get();

    expect(event1.exists).toBe(true);
    expect(event2.exists).toBe(true);
    expect(mockSend).toHaveBeenCalledTimes(2); // One multicast per user (multi-device support)
  });
});
