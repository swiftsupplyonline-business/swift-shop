import { defineSecret } from "firebase-functions/params";

// Define the MoPay API Key secret.
// This will be accessible to functions that explicitly declare it in their 'secrets' array.
export const MOPAY_API_KEY = defineSecret("MOPAY_API_KEY");

const MOPAY_BASE_URL = "https://mopay.co.ls";

export interface MopaySessionRequest {
    amount: number;
    reference: string;
    redirectUrl: string;
    description: string;
    customerEmail?: string;
    customerName?: string;
    notificationPhoneNumber?: string;
}

export interface MopaySessionResponse {
    success: boolean;
    sessionId?: string;
    paymentUrl?: string;
    reference?: string;
    amount?: number;
    message?: string;
}

export interface MopayVerifyResponse {
    sessionId: string;
    amount: number;
    reference: string;
    transactionStatus: string; // e.g., "SUCCESS", "FAILED", "PENDING"
    status: string;
    selectedPaymentMethod?: string;
    transactionId?: string;
    createdAt?: string;
    completedAt?: string;
}

export class MopayClient {
    /**
     * Initiates a payment session with MoPay.
     */
    static async initiatePaymentSession(
        request: MopaySessionRequest
    ): Promise<MopaySessionResponse> {
        const apiKey = MOPAY_API_KEY.value();

        const response = await fetch(`${MOPAY_BASE_URL}/api/external/payment`, {
            method: "POST",
            headers: {
                "Authorization": `Bearer ${apiKey}`,
                "Content-Type": "application/json",
            },
            body: JSON.stringify(request),
        });

        if (!response.ok) {
            const errorText = await response.text();
            console.error("MoPay Initiation Error:", errorText);
            return { success: false, message: `MoPay initiation failed: ${response.status}` };
        }

        return await response.json() as MopaySessionResponse;
    }

    /**
     * Verifies a payment session with MoPay.
     */
    static async verifyPaymentSession(
        sessionId: string
    ): Promise<MopayVerifyResponse | null> {
        const apiKey = MOPAY_API_KEY.value();

        const response = await fetch(`${MOPAY_BASE_URL}/api/external/session/v1/${sessionId}`, {
            method: "GET",
            headers: {
                "Authorization": `Bearer ${apiKey}`,
            },
        });

        if (!response.ok) {
            console.error(`MoPay Verification Error: ${response.status}`);
            return null;
        }

        return await response.json() as MopayVerifyResponse;
    }
}
