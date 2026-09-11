const admin = require("firebase-admin");
admin.initializeApp({ projectId: "swift-dev-3d3ae" });
const db = admin.firestore();

async function run() {
    const sessionId = "MOP_mtpx847i_LhaUUFtTUUSSwWV9ftug";
    console.log("Searching for sessionId:", sessionId);
    const s = await db.collection("orders").where("mopaySessionId", "==", sessionId).get();
    if (s.empty) {
        console.log("ORDER_NOT_FOUND");
    } else {
        s.forEach(d => {
            console.log(JSON.stringify({ id: d.id, ...d.data() }));
        });
    }
}
run().catch(console.error);
