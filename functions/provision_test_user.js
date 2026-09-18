const admin = require('firebase-admin');
if (admin.apps.length === 0) {
    admin.initializeApp({
        projectId: 'swift-dev-3d3ae'
    });
}

const db = admin.firestore();

async function provision() {
    const userId = 'mq9BwlLYu2h1mVzn9bffAL9dzW13';

    console.log(`Provisioning wallet for user ${userId}...`);
    const walletRef = db.collection('wallets').doc(userId);
    await walletRef.set({
        id: userId,
        userId: userId,
        availableBalanceMinorUnits: 0,
        pendingBalanceMinorUnits: 0,
        currency: 'LSL',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    console.log('Wallet provisioned.');

    console.log(`Verifying profile for user ${userId}...`);
    const profileRef = db.collection('profiles').doc(userId);
    const profileDoc = await profileRef.get();

    if (profileDoc.exists) {
        const data = profileDoc.data();
        console.log(`Current profile state: shopCount=${data.shopCount}, activeListingCount=${data.activeListingCount}`);

        if (data.shopCount !== 0 || data.activeListingCount !== 0) {
            console.log('Updating profile to set shopCount and activeListingCount to 0...');
            await profileRef.update({
                shopCount: 0,
                activeListingCount: 0,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            console.log('Profile updated.');
        } else {
            console.log('Profile already has correct counters.');
        }
    } else {
        console.log('Profile missing! Provisioning new profile...');
        await profileRef.set({
            uid: userId,
            displayName: 'Test User',
            shopCount: 0,
            activeListingCount: 0,
            tier: 'BASIC',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        console.log('Profile provisioned.');
    }
}

provision().then(() => {
    console.log('PROVISIONING_COMPLETE');
    process.exit(0);
}).catch(e => {
    console.error('PROVISIONING_FAILED:', e);
    process.exit(1);
});
