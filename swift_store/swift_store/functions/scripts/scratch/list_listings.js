const admin = require('firebase-admin');
admin.initializeApp({ projectId: 'swift-dev-3d3ae' });
const db = admin.firestore();

async function list() {
    const snapshot = await db.collection('listings').limit(10).get();
    if (snapshot.empty) {
        console.log("NO_LISTINGS");
    } else {
        snapshot.forEach(doc => {
            const data = doc.data();
            console.log(`ID: ${doc.id} | Title: ${data.title} | Seller: ${data.sellerId} | Price: ${data.priceMinorUnits}`);
        });
    }
}

list().catch(console.error);
