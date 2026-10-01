import * as admin from "firebase-admin";

// Initialize Admin SDK for server-authoritative operations
admin.initializeApp();

// Hardened exports
export { provisionNewUser } from "./auth";
export {
    calculateOrderFees, calculatePurchaseTotal, createPurchaseOrder, createOrder, verifyMopayPayment, confirmDelivery,
    updateOrderStatus, cancelOrder, confirmMopayPayment, initiateSubscription,
    createListing, deleteListing, createShop, updateShop, updateListing,
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
export { requestDelivery } from "./logistics";

// Canonical fulfillment boundary: delivery is independent of product purchase/payment.
export {
    getDeliveryOptions,
    createDeliveryRequest,
    acceptDeliveryRequest,
    declineDeliveryRequest,
    cancelDeliveryRequest,
    createDeliveryJob,
    updateDeliveryStatus,
    authorizeDriver,
    expireDeliveryRequests
} from "./fulfillment";

// Reservation Functions
export * from "./reservations";

// Advertising Functions
export * from "./advertising";

// Share Preview Functions
export * from "./sharePreview";

// Public web surfaces referenced by firebase.json hosting rewrites (/api/marketplace, /pay/**)
export { publicMarketplace } from "./publicMarketplace";
export { payPreview } from "./payPreview";

// Notifications: FCM token registration and Firestore-triggered push delivery
export {
    updateFcmToken,
    notifyOnMessage,
    notifyOnOrderStatusChange,
    notifyOnDeliveryRequestCreated,
    notifyOnDeliveryRequestResponded,
    notifyOnDeliveryStatusChange
} from "./notifications";
