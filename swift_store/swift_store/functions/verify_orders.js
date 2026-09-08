const admin = require('firebase-admin');

admin.initializeApp({
    projectId: 'swift-dev-3d3ae'
});

const db = admin.firestore();

async function listLatestOrders() {
    console.log('--- LATEST ORDERS ---');
    const snapshot = await db.collection('orders').orderBy('createdAt', 'desc').limit(5).get();
    if (snapshot.empty) {
        console.log('No orders found.');
        return;
    }

    snapshot.forEach(doc => {
        const data = doc.data();
        console.log(`ID: ${doc.id}`);
        console.log(`  Status: ${data.status}`);
        console.log(`  PaymentStatus: ${data.paymentStatus}`);
        console.log(`  ReservationReleased: ${data.reservationReleased}`);
        console.log(`  MopaySessionId: ${data.mopaySessionId}`);
        console.log(`  CreatedAt: ${data.createdAt ? data.createdAt.toDate().toISOString() : 'N/A'}`);
        console.log(`  Items: ${JSON.stringify(data.items)}`);
        console.log('-------------------');
    });
}

listLatestOrders().catch(console.error);
