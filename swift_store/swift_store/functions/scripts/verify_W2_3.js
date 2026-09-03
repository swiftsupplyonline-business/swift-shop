const admin = require('firebase-admin');

// Initialize with projectId only.
admin.initializeApp({
    projectId: 'swift-dev-3d3ae'
});

const db = admin.firestore();

async function verify() {
    console.log('--- W2.3 POST-INITIATION VERIFICATION ---');

    // Check Listing Stock
    const listingId = 'Verified Test Product 1787692776464';
    const lDoc = await db.collection('listings').doc(listingId).get();
    if (!lDoc.exists) {
        console.error(`Listing ${listingId} not found.`);
    } else {
        console.log(`Stock for ${listingId}: ${lDoc.data().stockQuantity}`);
    }

    // Check Latest Order
    const orders = await db.collection('orders').orderBy('createdAt', 'desc').limit(1).get();
    if (orders.empty) {
        console.log('No orders found.');
    } else {
        const order = orders.docs[0];
        console.log(`Latest Order ID: ${order.id}`);
        console.log(`Status: ${order.data().status}`);
        console.log(`Error: ${order.data().error || 'None'}`);
        console.log(`MoPay Session ID: ${order.data().mopaySessionId || 'None'}`);
    }
}

verify().catch(e => console.error(e));
