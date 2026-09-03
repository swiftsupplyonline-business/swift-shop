const admin = require("firebase-admin");
if (admin.apps.length === 0) { admin.initializeApp(); }
const db = admin.firestore();

const EXPECTED_FIELDS = [
  "id", "shopId", "sellerId", "title", "description", "category",
  "priceMinorUnits", "isAvailable", "stockQuantity", "images",
  "createdAt", "updatedAt"
];

function typeOf(val) {
  if (val instanceof admin.firestore.Timestamp) return "Timestamp";
  if (Array.isArray(val)) return `Array(${val.length})`;
  if (val === null) return "null";
  if (val === undefined) return "undefined";
  return typeof val;
}

async function inspect() {
  const snap = await db.collection("listings").get();
  console.log(`\n=== LISTINGS: ${snap.size} total docs ===\n`);

  const shopIds = new Set();
  const sellerIds = new Set();
  const fieldTypeMap = {}; // field -> Set of types seen across all docs
  const perDocIssues = [];

  snap.forEach(doc => {
    const data = doc.data();
    const issues = [];

    // Track every field's type
    for (const [field, val] of Object.entries(data)) {
      if (!fieldTypeMap[field]) fieldTypeMap[field] = new Set();
      fieldTypeMap[field].add(typeOf(val));
    }

    // Missing expected fields
    for (const field of EXPECTED_FIELDS) {
      if (!(field in data)) issues.push(`MISSING: ${field}`);
    }

    // Sanity checks
    if (typeof data.priceMinorUnits !== "number" || data.priceMinorUnits <= 0) {
      issues.push(`BAD priceMinorUnits: ${JSON.stringify(data.priceMinorUnits)}`);
    }
    if (typeof data.stockQuantity !== "number" || data.stockQuantity < 0) {
      issues.push(`BAD stockQuantity: ${JSON.stringify(data.stockQuantity)}`);
    }
    if (data.images !== undefined && !Array.isArray(data.images)) {
      issues.push(`BAD images (not array): ${JSON.stringify(data.images)}`);
    }
    if (data.images && Array.isArray(data.images) && data.images.length === 0) {
      issues.push(`WARN: images array is empty`);
    }
    if (typeof data.isAvailable !== "boolean") {
      issues.push(`BAD isAvailable: ${JSON.stringify(data.isAvailable)}`);
    }
    if (!data.shopId) issues.push(`MISSING shopId (orphaned listing)`);
    if (!data.sellerId) issues.push(`MISSING sellerId`);

    if (data.shopId) shopIds.add(data.shopId);
    if (data.sellerId) sellerIds.add(data.sellerId);

    if (issues.length > 0) {
      perDocIssues.push({ docId: doc.id, title: data.title || "(no title)", issues });
    }
  });

  console.log("--- FIELD TYPE CONSISTENCY (field -> types seen across all docs) ---");
  for (const [field, types] of Object.entries(fieldTypeMap)) {
    const typesArr = [...types];
    const flag = typesArr.length > 1 ? "  <-- INCONSISTENT TYPES" : "";
    console.log(`${field}: ${typesArr.join(", ")}${flag}`);
  }

  console.log("\n--- PER-DOCUMENT ISSUES ---");
  if (perDocIssues.length === 0) {
    console.log("No issues found on any listing doc.");
  } else {
    perDocIssues.forEach(d => {
      console.log(`\n${d.docId} ("${d.title}")`);
      d.issues.forEach(i => console.log(`  - ${i}`));
    });
  }

  console.log("\n--- REFERENTIAL INTEGRITY: shopId -> shops collection ---");
  for (const shopId of shopIds) {
    const shopDoc = await db.collection("shops").doc(shopId).get();
    console.log(`shopId ${shopId}: ${shopDoc.exists ? "OK" : "DANGLING (shop does not exist)"}`);
  }

  console.log("\n--- REFERENTIAL INTEGRITY: sellerId -> users collection ---");
  for (const sellerId of sellerIds) {
    const userDoc = await db.collection("users").doc(sellerId).get();
    console.log(`sellerId ${sellerId}: ${userDoc.exists ? "OK" : "DANGLING (user does not exist)"}`);
  }

  console.log(`\n=== SUMMARY: ${perDocIssues.length} of ${snap.size} listings have at least one issue ===`);
  process.exit(0);
}

inspect().catch(e => { console.error("INSPECT_FAILED:", e); process.exit(1); });
