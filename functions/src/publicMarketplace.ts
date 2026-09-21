import { onRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

const publicFields = (data: FirebaseFirestore.DocumentData, id: string) => ({
    id,
    ...data,
    createdAt: data.createdAt?.toMillis?.() ?? null,
    updatedAt: data.updatedAt?.toMillis?.() ?? null
});

export const publicMarketplace = onRequest({ cors: true }, async (_request, response) => {
    try {
        const db = admin.firestore();
        const [listingSnap, shopSnap, postSnap] = await Promise.all([
            db.collection("listings").limit(100).get(),
            db.collection("shops").limit(100).get(),
            db.collection("posts").limit(100).get()
        ]);

        const listings = listingSnap.docs
            .map(d => publicFields(d.data(), d.id))
            .filter((x: any) => x.isAvailable !== false)
            .sort((a: any, b: any) => (b.createdAt || b.updatedAt || 0) - (a.createdAt || a.updatedAt || 0));

        const shops = shopSnap.docs
            .map(d => publicFields(d.data(), d.id))
            .filter((x: any) => x.isActive !== false)
            .sort((a: any, b: any) => (b.createdAt || b.updatedAt || 0) - (a.createdAt || a.updatedAt || 0));

        const posts = postSnap.docs
            .map(d => publicFields(d.data(), d.id))
            .sort((a: any, b: any) => (b.createdAt || b.updatedAt || 0) - (a.createdAt || a.updatedAt || 0));

        response.set("Cache-Control", "public, max-age=15, s-maxage=15");
        response.status(200).json({
            ok: true,
            generatedAt: Date.now(),
            listings,
            shops,
            posts
        });
    } catch (error: any) {
        console.error("publicMarketplace failed", error);
        response.status(500).json({
            ok: false,
            error: "Marketplace data is temporarily unavailable."
        });
    }
});
