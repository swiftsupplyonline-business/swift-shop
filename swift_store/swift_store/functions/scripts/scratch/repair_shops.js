const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');

initializeApp({
    projectId: 'swift-dev-3d3ae'
});

const db = getFirestore();

async function repair() {
    const merchantId = 'JiQLvPsVnrOAcn5XEcCinr79S542';
    const shops = [
        {
            id: 'personal_shop_JiQLvPsVnrOAcn5XEcCinr79S542',
            ownerId: merchantId,
            name: 'Personal Shop',
            description: 'Verified Shop for Merchant JiQLvPsVnrOAcn5XEcCinr79S542',
            category: 'Motor homes',
            locationLat: -29.3150,
            locationLng: 27.4869,
            locationAddress: 'Maseru, Lesotho',
            isVerified: true,
            isActive: true,
            rating: 5.0,
            reviewCount: 0,
            followerCount: 0,
            listingCount: 1,
            createdAt: admin.firestore.FieldValue.serverTimestamp()
        },
        {
            id: 'test_shop_1',
            ownerId: merchantId,
            name: 'Test Shop 1',
            description: 'Seeded Test Shop',
            category: 'General',
            locationLat: -29.3150,
            locationLng: 27.4869,
            locationAddress: 'Maseru, Lesotho',
            isVerified: true,
            isActive: true,
            rating: 4.5,
            reviewCount: 0,
            followerCount: 0,
            listingCount: 8,
            createdAt: admin.firestore.FieldValue.serverTimestamp()
        }
    ];

    for (const shopData of shops) {
        console.log(`Checking shop ${shopData.id}...`);
        const docRef = db.collection('shops').doc(shopData.id);
        const doc = await docRef.get();

        if (!doc.exists) {
            console.log(`Creating shop ${shopData.id}...`);
            await docRef.set(shopData);
            console.log(`Shop ${shopData.id} created.`);
        } else {
            console.log(`Shop ${shopData.id} already exists.`);
        }
    }

    // Sync counters
    console.log(`Syncing profile counters for ${merchantId}...`);
    const listingsCount = await db.collection('listings').where('sellerId', '==', merchantId).where('isAvailable', '==', true).get();
    const shopsCount = await db.collection('shops').where('ownerId', '==', merchantId).get();

    await db.collection('profiles').doc(merchantId).set({
        activeListingCount: listingsCount.size,
        shopCount: shopsCount.size,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
    console.log('Profile counters synced.');
}

repair().catch(e => {
    console.error('Repair failed:', e);
    process.exit(1);
});

