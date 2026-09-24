import firebaseTest from 'firebase-functions-test';
import * as admin from 'firebase-admin';
import { notifyOnMessage } from '../notifications';

const testEnv = firebaseTest({
  projectId: 'demo-swift-shop-reconciled',
});

// Mock only the FCM transport. Keep the real firebase-admin Firestore API.
const mockSend = jest.fn();
jest.spyOn(admin, 'messaging').mockReturnValue({
  sendEachForMulticast: mockSend,
} as any);

describe('Notifications Accounting (SWIFT-022)', () => {
  const db = admin.firestore();

  afterAll(() => {
    testEnv.cleanup();
    mockSend.mockReset();
    jest.restoreAllMocks();
  });

  test('notifyOnMessage - Multi-device accounting correctly handles partial failure', async () => {
    const userId = 'user_target';
    const conversationId = 'conv_123';
    const eventId = 'event_456';

    const userRef = db.collection('users').doc(userId);
    await userRef.collection('devices').doc('device_ok').set({ token: 't1', isActive: true });
    await userRef.collection('devices').doc('device_bad').set({ token: 't2', isActive: true });

    await db.collection('conversations').doc(conversationId).set({
      participantIds: ['sender_uid', userId]
    });

    mockSend.mockResolvedValue({
      successCount: 1,
      failureCount: 1,
      responses: [
        { success: true, messageId: 'm1' },
        { success: false, error: { code: 'messaging/registration-token-not-registered', message: 'unregistered' } }
      ]
    });

    const wrapped = testEnv.wrap(notifyOnMessage);
    const snap = testEnv.firestore.makeDocumentSnapshot({
      conversationId,
      senderId: 'sender_uid',
      text: 'Hello!'
    }, 'messages/msg_123');

    await wrapped({
      data: snap,
      id: eventId,
      params: { messageId: 'msg_123' }
    } as any);

    const eventDoc = await db.collection('notificationEvents').doc(`${eventId}_${userId}`).get();
    const accounting = eventDoc.data()?.deviceAccounting;

    expect(accounting['device_ok'].status).toBe('SENT');
    expect(accounting['device_bad'].status).toBe('FAILED_PERMANENT');
    expect(eventDoc.data()?.status).toBe('SENT');

    const deviceBadDoc = await userRef.collection('devices').doc('device_bad').get();
    expect(deviceBadDoc.data()?.isActive).toBe(false);
  });

  test('notifyOnMessage - Correctly fan-out to multiple recipients in group chat', async () => {
    const conversationId = 'group_conv_123';
    const eventId = 'group_event_789';

    await db.collection('conversations').doc(conversationId).set({
      participantIds: ['sender_uid', 'user_1', 'user_2']
    });

    await db.collection('users').doc('user_1').collection('devices').doc('d1').set({ token: 't1', isActive: true });
    await db.collection('users').doc('user_2').collection('devices').doc('d2').set({ token: 't2', isActive: true });

    mockSend.mockResolvedValue({
      successCount: 1,
      failureCount: 0,
      responses: [{ success: true }]
    });

    const wrapped = testEnv.wrap(notifyOnMessage);
    const snap = testEnv.firestore.makeDocumentSnapshot({
      conversationId,
      senderId: 'sender_uid',
      text: 'Group message'
    }, 'messages/group_msg_1');

    await wrapped({
      data: snap,
      id: eventId,
      params: { messageId: 'group_msg_1' }
    } as any);

    const event1 = await db.collection('notificationEvents').doc(`${eventId}_user_1`).get();
    const event2 = await db.collection('notificationEvents').doc(`${eventId}_user_2`).get();

    expect(event1.exists).toBe(true);
    expect(event2.exists).toBe(true);
    expect(mockSend).toHaveBeenCalledTimes(2);
  });
});
