const admin = require("firebase-admin");
if (admin.apps.length === 0) { admin.initializeApp(); }
const db = admin.firestore();
const { Timestamp } = admin.firestore;

const REPAIRS = [
  { collection: "shops", docId: "personal_shop_JiQLvPsVnrOAcn5XEcCinr79S542", createdAtMillis: 1787703829430 },
  { collection: "shops", docId: "test_shop_1", createdAtMillis: 1787703829430 },
  { collection: "listings", docId: "Gy0rGwdY5uSqjiqskI8Q", createdAtMillis: 1787677893863, fixUpdatedAt: true },
  { collection: "listings", docId: "Jn4Ej6uvrkpXkT4y4hwW", createdAtMillis: 1787692776465, fixUpdatedAt: true },
  { collection: "listings", docId: "P42NmmhXVgGGxwcG7Rkv", createdAtMillis: 1787703829430 },
  { collection: "listings", docId: "Q5uGGum2XiZ65Cq7KTsp", createdAtMillis: 1787677941276, fixUpdatedAt: true },
  { collection: "listings", docId: "SDaXTuMZX7wB73TyqW9G", createdAtMillis: 1787677854207, fixUpdatedAt: true },
  { collection: "listings", docId: "bBCtIWnY1I5IfZoMIKXL", createdAtMillis: 1787677943483, fixUpdatedAt: true },
  { collection: "listings", docId: "eDiwi91LCiem7qMcadFB", createdAtMillis: 1787677935701, fixUpdatedAt: true },
  { collection: "listings", docId: "iGywMwFv7b633c8NEMvI", createdAtMillis: 1787677938970, fixUpdatedAt: true },
  { collection: "listings", docId: "yliV3gIsVp1TlmvIB3JJ", createdAtMillis: 1787692484763, fixUpdatedAt: true },
];

const DRY_RUN = !process.argv.includes("--apply");

async function repair() {
  console.log(DRY_RUN ? "DRY RUN — no writes will be made. Pass --apply to commit.\n" : "APPLYING WRITES.\n");

  for (const r of REPAIRS) {
    const ref = db.collection(r.collection).doc(r.docId);
    const doc = await ref.get();
    if (!doc.exists) {
      console.log(`SKIP (not found): ${r.collection}/${r.docId}`);
      continue;
    }

    const createdAtTs = Timestamp.fromMillis(r.createdAtMillis);
    const update = { createdAt: createdAtTs };
    if (r.fixUpdatedAt) {
      update.updatedAt = createdAtTs; // best-available approximation: never genuinely updated since creation
    }

    console.log(`${DRY_RUN ? "[DRY]" : "[APPLY]"} ${r.collection}/${r.docId} ->`, {
      createdAt: createdAtTs.toDate().toISOString(),
      ...(r.fixUpdatedAt ? { updatedAt: createdAtTs.toDate().toISOString() } : {}),
    });

    if (!DRY_RUN) {
      await ref.update(update);
    }
  }

  console.log(DRY_RUN ? "\nDRY RUN COMPLETE. Review above, then rerun with --apply." : "\nREPAIR_COMPLETE.");
  process.exit(0);
}

repair().catch(e => { console.error("REPAIR_FAILED:", e); process.exit(1); });
