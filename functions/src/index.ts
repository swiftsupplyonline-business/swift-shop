import * as admin from "firebase-admin";

// Initialize Admin SDK for server-authoritative operations
admin.initializeApp();

// Auth Triggers
export * from "./auth";

// Financial Functions
export * from "./finance";

// Commerce Functions
export * from "./commerce";

// Social Functions
export * from "./social";

// Maintenance Functions
export * from "./maintenance";

// Logistics Functions
export * from "./logistics";

// Advertising Functions
export * from "./advertising";

