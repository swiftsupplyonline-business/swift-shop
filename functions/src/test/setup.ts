import * as admin from 'firebase-admin';

if (admin.apps.length === 0) {
  admin.initializeApp({
    projectId: process.env.GCLOUD_PROJECT || 'demo-swift-shop-reconciled',
  });
}
