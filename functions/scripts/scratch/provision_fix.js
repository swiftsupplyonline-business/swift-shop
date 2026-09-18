const admin = require('firebase-admin');

async function fix() {
    if (admin.apps.length === 0) {
        admin.initializeApp({
            projectId: 'swift-dev-3d3ae'
        });
    }

    const db = admin.firestore();
    const uid = 'mq9BwlLYu2h1mVzn9bffAL9dzW13';

    console.log(`Fixing provisioning for: ${uid}`);

    // 1. Check/Fix User document
    const userRef = db.collection('users').doc(uid);
    const userDoc = await userRef.get();

    if (!userDoc.exists) {
        console.log('Creating user document...');
        await userRef.set({
            uid: uid,
            email: 'test@example.com',
            accountStatus: 'ACTIVE',
            tier: 'BASIC',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
    } else {
        console.log('User document exists. Ensuring accountStatus is ACTIVE...');
        await userRef.update({
            accountStatus: 'ACTIVE',
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
    }

    // 2. Check/Fix Profile document
    const profileRef = db.collection('profiles').doc(uid);
    const profileDoc = await profileRef.get();

    if (!profileDoc.exists) {
        console.log('Creating profile document...');
        await profileRef.set({
            displayName: 'Test User',
            bio: 'Test account bio',
            location: 'Maseru, Lesotho',
            followerCount: 0,
            followingCount: 0,
            shopCount: 0,
            postCount: 0,
            activeListingCount: 0,
            reputationScore: 0,
            tier: 'BASIC',
            totalDeliveries: 0,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
    } else {
        console.log('Profile document exists.');
    }

    console.log('Provisioning check complete.');
}

fix().then(() => {
    console.log('PROVISION_FIX_DONE');
}).catch(e => {
    console.error('PROVISION_FIX_FAILED:', e);
});
