const admin = require("firebase-admin");
if (admin.apps.length === 0) { admin.initializeApp(); }
const db = admin.firestore();

const TARGETS = [
  { collection: "shops", fields: ["createdAt", "updatedAt"] },
  { collection: "listings", fields: ["createdAt", "updatedAt"] },
  { collection: "profiles", fields: ["createdAt", "updatedAt"] },
];

async function scan() {
  for (const { collection, fields } of TARGETS) {
    const snap = await db.collection(collection).get();
    const offenders = [];

    snap.forEach(doc => {
      const data = doc.data();
      for (const field of fields) {
        if (!(field in data)) continue;
        const val = data[field];
        const isTimestamp = val instanceof admin.firestore.Timestamp;
        if (!isTimestamp && val !== null && val !== undefined) {
          offenders.push({ docId: doc.id, field, jsType: typeof val, value: val });
        }
      }
    });

    console.log(`\n=== ${collection} (${snap.size} docs, ${offenders.length} offending fields) ===`);
    console.log(offenders);
  }
  process.exit(0);
}

scan().catch(e => { console.error("SCAN_FAILED:", e); process.exit(1); });
