const admin = require('firebase-admin');
admin.initializeApp();
const db = admin.firestore();
const uid = "F3ZWVQ3CcoVFgQGDxLK8Xgcpr6T2";
(async () => {
    const userDoc = await db.collection('users').doc(uid).get();
    console.log('User Doc:', userDoc.exists ? userDoc.data() : 'MISSING');
    const walletDoc = await db.collection('wallets').doc(uid).get();
    console.log('Wallet Doc:', walletDoc.exists ? walletDoc.data() : 'MISSING');
})();
