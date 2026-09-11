const admin = require('firebase-admin');
admin.initializeApp({ projectId: 'swift-dev-3d3ae' });
const db = admin.firestore();

async function run() {
    console.log('--- DELIVERY LISTINGS IN swift-dev-3d3ae ---');
    const snap = await db.collection('listings').where('listingType', '==', 'DELIVER').get();
    if (snap.empty) {
        console.log('No delivery listings found.');
    } else {
        snap.forEach(doc => {
            const data = doc.data();
            console.log(`ID: ${doc.id}`);
            console.log(`Title: ${data.title}`);
            console.log(`Price: LSL ${data.priceMinorUnits / 100}`);
            console.log(`Available: ${data.isAvailable}`);
            console.log('---');
        });
    }
}

run().catch(console.error);
