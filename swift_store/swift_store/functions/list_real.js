const admin = require("firebase-admin");
admin.initializeApp({ projectId: "swift-dev-3d3ae" });
const db = admin.firestore();

async function run() {
    const s = await db.collection("listings").get();
    s.forEach(d => {
        const data = d.data();
        if (data.title && !d.id.startsWith("listing_test")) {
            console.log(JSON.stringify({ id: d.id, title: data.title, sellerId: data.sellerId, price: data.priceMinorUnits, stock: data.stockQuantity }));
        }
    });
}
run().catch(console.error);
