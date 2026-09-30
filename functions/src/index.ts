import * as admin from "firebase-admin";

// Initialize Admin SDK for server-authoritative operations
admin.initializeApp();

// Hardened exports
export { provisionNewUser } from "./auth";
export {
    calculateOrderFees, calculatePurchaseTotal, createPurchaseOrder, createOrder, verifyMopayPayment, confirmDelivery,
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

// Legacy logistics endpoints retained for compatibility.
export { requestDelivery, expireDeliveryRequests } from "./logistics";

// Canonical fulfillment boundary: delivery is independent of product purchase/payment.
export {
    getDeliveryOptions,
    createDeliveryRequest,
    acceptDeliveryRequest,
    declineDeliveryRequest,
    cancelDeliveryRequest,
    createDeliveryJob,
    updateDeliveryStatus,
    authorizeDriver
} from "./fulfillment";

// Reservation Functions
export * from "./reservations";

// Advertising Functions
export * from "./advertising";

// Share Preview Functions
export * from "./sharePreview";

