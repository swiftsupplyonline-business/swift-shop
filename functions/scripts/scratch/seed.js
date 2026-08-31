const admin = require('firebase-admin');
const { getFirestore } = require('firebase-admin/firestore');

admin.initializeApp({
    projectId: 'swift-dev-3d3ae'
});

const db = getFirestore();

async function seed() {
    try {
        const docRef = await db.collection('listings').add({
            title: 'Available Product',
            priceMinorUnits: 10000,
            priceCurrency: 'LSL',
            isAvailable: true,
            shopId: 'test_shop',
            sellerId: 'test_seller',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            commitmentCount: 0,
            deliveryEstimateDays: 2,
            imageUrls: [],
            description: 'Test product for Phase 5 verification.',
            listingType: 'BUY',
            tags: ['test', 'dev']
        });
        console.log('Seeded listing ID:', docRef.id);

        await db.collection('listings').add({
            title: 'Out of Stock Product',
            priceMinorUnits: 5000,
            priceCurrency: 'LSL',
            isAvailable: false,
            shopId: 'test_shop',
            sellerId: 'test_seller',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            commitmentCount: 5,
            deliveryEstimateDays: 5,
            imageUrls: [],
            description: 'This product should be filtered out by getShopFeed.',
            listingType: 'BUY',
            tags: ['test', 'unavailable']
        });
        console.log('Seeded unavailable listing.');

    } catch (e) {
        console.error('Seeding failed:', e.message);
    }
}
seed();

