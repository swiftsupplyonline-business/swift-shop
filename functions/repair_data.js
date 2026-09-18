const admin = require('firebase-admin');
if (admin.apps.length === 0) {
    admin.initializeApp();
}

const db = admin.firestore();

async function repair() {
    const merchantId = 'mq9BwlLYu2h1mVzn9bffAL9dzW13';
    console.log(`Checking user ${merchantId}...`);
    const userRef = db.collection('users').doc(merchantId);
    await userRef.set({
        uid: merchantId,
        accountStatus: 'ACTIVE',
        tier: 'BASIC',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
    console.log('User status updated to ACTIVE.');

    console.log(`Checking profile ${merchantId}...`);
    const profileRef = db.collection('profiles').doc(merchantId);
    const profileDoc = await profileRef.get();
    if (!profileDoc.exists) {
        console.log('Provisioning profile...');
        await profileRef.set({
            uid: merchantId,
            displayName: 'Test User',
            tier: 'BASIC',
            shopCount: 0,
            activeListingCount: 0,
            createdAt: admin.firestore.FieldValue.serverTimestamp()
        });
        console.log('Profile created.');
    } else {
        console.log('Profile already exists.');
    }
}

console.log('Starting repair...');
repair().then(() => {
    console.log('REPAIR_COMPLETE_SIGNAL');
}).catch(e => {
    console.error('REPAIR_FAILED_SIGNAL:', e);
});
