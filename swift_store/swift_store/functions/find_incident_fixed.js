const admin = require("firebase-admin");

admin.initializeApp({
  projectId: "swift-dev-3d3ae"
});

const db = admin.firestore();

async function findIncident() {
  const listings = await db.collection("listings").get();
  for (const doc of listings.docs) {
    if (doc.data().stockQuantity === 99) {
        console.log(`POTENTIAL INCIDENT LISTING: ${doc.id} (${doc.data().title})`);
        const orders = await db.collection("orders")
            .where("items", "array-contains-any", [{listingId: doc.id}]) // This might not work with objects
            .get();
        // Since array-contains-any with objects is tricky, I'll just filter manually
        const allOrders = await db.collection("orders").where("status", "==", "PENDING").get();
        for (const oDoc of allOrders.docs) {
            const items = oDoc.data().items || [];
            if (items.some(it => it.listingId === doc.id)) {
                console.log(`INCIDENT ORDER FOUND: ${oDoc.id}, Session: ${oDoc.data().mopaySessionId}`);
            }
        }
    }
  }
}

findIncident().catch(console.error);
