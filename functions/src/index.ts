import * as admin from "firebase-admin";

// Initialize Admin SDK for server-authoritative operations
admin.initializeApp();

// Hardened exports
export { provisionNewUser } from "./auth";
export {
    calculateOrderFees, createOrder, verifyMopayPayment, confirmDelivery,
    updateOrderStatus, cancelOrder, confirmMopayPayment, initiateSubscription,
    createListing, deleteListing, createShop, updateListing,
    createListingComment, deleteListingComment
} from "./commerce";
export {
    onFollowCreated, onFollowDeleted,
    publishPost, likePost, unlikePost, bookmarkPost, unbookmarkPost,
    likeListing, unlikeListing, bookmarkListing, unbookmarkListing,
    createPostComment, deletePostComment
} from "./social";
export { syncProfileCounters } from "./maintenance";

// Financial Functions
export * from "./finance";

// Logistics Functions
export * from "./logistics";

// Reservation Functions
export * from "./reservations";

// Advertising Functions
export * from "./advertising";

// Share Preview Functions
export * from "./sharePreview";

