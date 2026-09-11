const admin = require('firebase-admin');
process.env.FIRESTORE_EMULATOR_HOST = undefined;
admin.initializeApp({
    projectId: 'swift-dev-3d3ae'
});
const db = admin.firestore();
async function run() {
    try {
        const snapshot = await db.collection('users').limit(10).get();
        if (snapshot.empty) {
            console.log('No users found.');
            return;
        }
        snapshot.forEach(doc => {
            console.log(doc.id, '=>', doc.data().email);
        });
    } catch (e) {
        console.error('Error fetching users:', e.message);
    }
}
run();
