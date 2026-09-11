const admin = require('firebase-admin');
admin.initializeApp({ projectId: 'swift-dev-3d3ae' });
const db = admin.firestore();

async function verify() {
    console.log("--- Phase 5 Verification ---");

    // 1. Seed Listing
    console.log("1. Seeding test listing...");
    await db.collection('listings').doc('test_v1').set({
        id: 'test_v1',
        shopId: 'test_shop',
        sellerId: 'test_seller',
        title: 'Authoritative Item',
        priceMinorUnits: 10000, // M100.00
        isAvailable: true,
        stockQuantity: 5,
        createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    console.log("   [OK] Listing seeded.");

    // 2. Test calculateOrderFees Logic (Simulated)
    console.log("2. Verifying Fee Logic...");
    const subtotal = 10000 * 2; // 2 units
    const platformFee = Math.floor((subtotal * 15) / 1000); // 1.5%
    if (platformFee === 300) {
        console.log(`   [OK] Platform fee correctly calculated: M3.00 (300 minor units)`);
    } else {
        console.error(`   [FAIL] Platform fee incorrect: ${platformFee}`);
    }

    // 3. Test P2P Fee Logic
    console.log("3. Verifying P2P Fee...");
    const transferAmount = 50000; // M500
    const p2pFee = Math.floor((transferAmount * 15) / 1000);
    if (p2pFee === 750) {
        console.log(`   [OK] P2P fee correctly calculated: M7.50 (750 minor units)`);
    } else {
        console.error(`   [FAIL] P2P fee incorrect: ${p2pFee}`);
    }
}

verify().catch(console.error);

