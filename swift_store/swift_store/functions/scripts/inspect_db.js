const admin = require('firebase-admin');
const serviceAccount = require('../app/src/dev/google-services.json'); // This isn't a service account, won't work for admin SDK.

// I'll try to initialize using the default environment credentials if available,
// otherwise I'll need to use the project ID and hope for the best if I have default auth.
admin.initializeApp({
    projectId: 'swift-dev-3d3ae'
});

const db = admin.firestore();

async function inspect() {
    console.log('--- USERS ---');
    const users = await db.collection('users').limit(5).get();
    if (users.empty) console.log('No users found.');
    users.forEach(doc => console.log(doc.id, doc.data()));

    console.log('\n--- LISTINGS ---');
    const listings = await db.collection('listings').limit(5).get();
    if (listings.empty) console.log('No listings found.');
    listings.forEach(doc => console.log(doc.id, doc.data()));

    console.log('\n--- WALLETS ---');
    const wallets = await db.collection('wallets').limit(5).get();
    if (wallets.empty) console.log('No wallets found.');
    wallets.forEach(doc => console.log(doc.id, doc.data()));
}

inspect().catch(e => console.error(e));
