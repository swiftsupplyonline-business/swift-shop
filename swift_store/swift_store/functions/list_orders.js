const admin = require("firebase-admin");
admin.initializeApp({ projectId: "swift-dev-3d3ae" });
const db = admin.firestore();

async function run() {
    const s = await db.collection("orders").limit(5).get();
    if (s.empty) {
        console.log("NO_ORDERS");
    } else {
        s.forEach(d => {
            console.log(JSON.stringify({ id: d.id, ...d.data() }));
        });
    }
}
run().catch(console.error);
