const admin = require('firebase-admin');
admin.initializeApp({ projectId: 'swift-dev-3d3ae' });
const db = admin.firestore();

async function check() {
    const snapshot = await db.collection('listings').where('title', '==', 'RUNTIME_TEST_2315').get();
    if (snapshot.empty) {
        console.log("NOT_FOUND");
    } else {
        snapshot.forEach(doc => {
            console.log("FOUND");
            console.log(JSON.stringify(doc.data(), null, 2));
        });
    }
}

check().catch(console.error);
