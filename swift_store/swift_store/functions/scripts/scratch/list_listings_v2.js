const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');

initializeApp({ projectId: 'swift-dev-3d3ae' });
const db = getFirestore();

async function list() {
    console.log("Checking for real listings...");
    const snapshot = await db.collection('listings').limit(20).get();
    if (snapshot.empty) {
        console.log("NO_LISTINGS");
    } else {
        snapshot.forEach(doc => {
            const data = doc.data();
            if (!doc.id.startsWith('listing_test')) {
                console.log(`REAL_LISTING: ID: ${doc.id} | Title: ${data.title} | Seller: ${data.sellerId} | Price: ${data.priceMinorUnits} | Stock: ${data.stockQuantity}`);
            } else {
                console.log(`SEED_LISTING: ID: ${doc.id} | Title: ${data.title}`);
            }
        });
    }
}

list().catch(console.error);
