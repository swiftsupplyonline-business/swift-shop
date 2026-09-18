const admin = require('firebase-admin');

if (admin.apps.length === 0) {
    admin.initializeApp({
        projectId: 'swift-dev-3d3ae'
    });
}

const db = admin.firestore();
const uid = 'mq9BwlLYu2h1mVzn9bffAL9dzW13';

async function inspect() {
    console.log(`Inspecting account: ${uid}`);

    const userDoc = await db.collection('users').doc(uid).get();
    if (userDoc.exists) {
        console.log('User document found:', JSON.stringify(userDoc.data(), null, 2));
    } else {
        console.log('User document NOT found.');
    }

    const profileDoc = await db.collection('profiles').doc(uid).get();
    if (profileDoc.exists) {
        console.log('Profile document found:', JSON.stringify(profileDoc.data(), null, 2));
    } else {
        console.log('Profile document NOT found.');
    }
}

inspect().then(() => process.exit(0)).catch(e => {
    console.error(e);
    process.exit(1);
});
