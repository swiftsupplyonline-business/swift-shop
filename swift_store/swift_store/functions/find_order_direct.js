const admin = require("firebase-admin");
admin.initializeApp({ projectId: "swift-dev-3d3ae" });
const db = admin.firestore();

async function run() {
    const orderId = "LhaUUFtTUUSSwWV9ftug";
    console.log("Searching for orderId:", orderId);
    const d = await db.collection("orders").doc(orderId).get();
    if (!d.exists) {
        console.log("ORDER_NOT_FOUND");
    } else {
        const orderData = d.data();
        console.log("ORDER_DATA:" + JSON.stringify({ id: d.id, ...orderData }));

        // Task 2: Identify listingId
        const listingId = orderData.items && orderData.items[0] ? orderData.items[0].listingId : orderData.listingId;
        console.log("LISTING_ID:" + listingId);

        if (listingId) {
            // Task 3: Report current stockQuantity
            const l = await db.collection("listings").doc(listingId).get();
            if (l.exists) {
                console.log("LISTING_DATA:" + JSON.stringify({ id: l.id, ...l.data() }));
            }

            // Task 4: Search for other orders created in the last 2 hours for that same listingId
            const twoHoursAgo = new Date(Date.now() - 2 * 60 * 60 * 1000);
            const otherOrders = await db.collection("orders")
                .where("items", "array-contains", { listingId: listingId }) // This might not work if items contains more than just listingId
                .where("createdAt", ">=", twoHoursAgo)
                .get();

            // If the above query fails, try alternative
            const allRecentOrders = await db.collection("orders")
                .where("createdAt", ">=", twoHoursAgo)
                .get();

            const matchingOrders = [];
            allRecentOrders.forEach(doc => {
                const data = doc.data();
                const hasListing = (data.items || []).some(item => item.listingId === listingId) || data.listingId === listingId;
                if (hasListing && doc.id !== orderId) {
                    matchingOrders.push({ id: doc.id, createdAt: data.createdAt, paymentStatus: data.paymentStatus });
                }
            });
            console.log("OTHER_ORDERS:" + JSON.stringify(matchingOrders));
        }
    }
}
run().catch(console.error);
