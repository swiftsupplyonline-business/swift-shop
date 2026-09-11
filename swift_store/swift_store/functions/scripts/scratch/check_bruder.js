const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');

initializeApp({ projectId: 'swift-dev-3d3ae' });
const db = getFirestore();

async function check() {
    const snapshot = await db.collection('listings').where('title', '==', 'Bruder caravan').get();
    if (snapshot.empty) {
        console.log("NOT_FOUND");
    } else {
        snapshot.forEach(doc => {
            const data = doc.data();
            console.log(`FOUND: ID: ${doc.id} | Seller: ${data.sellerId} | Price: ${data.priceMinorUnits}`);
        });
    }
}

check().catch(console.error);
