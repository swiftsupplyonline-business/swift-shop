export interface Listing {
  id: string;
  title: string;
  description: string;
  imageUrls: string[];
  priceMinorUnits: number;
  priceCurrency: string;
  isAvailable: boolean;
  shopId: string;
  listingType: string;
}

export interface Shop {
  id: string;
  name: string;
  description: string;
  logoUrl?: string;
  imageUrl?: string;
  location?: string;
  city?: string;
  ownerId: string;
}

export interface Profile {
  id: string;
  displayName?: string;
  name?: string;
  avatarUrl?: string;
  photoUrl?: string;
  bio?: string;
}

export function formatPrice(minorUnits: number, currency = "LSL"): string {
  return `${currency} ${(minorUnits / 100).toFixed(2)}`;
}
