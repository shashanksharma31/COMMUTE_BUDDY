export interface DelhiPlace {
  id: string;
  name: string;
  area: string;
  category: string;
  lat: number;
  lng: number;
  stationId?: string;
}

// Hardcoded DELHI_PLACES removed per Truth-Over-Completeness policy.
export const DELHI_PLACES: DelhiPlace[] = [];

let backendBaseUrl = 'http://127.0.0.1:3001';

export function setBackendBaseUrl(url: string): void {
  backendBaseUrl = url.replace(/\/+$/, '');
}

export function getBackendBaseUrl(): string {
  return backendBaseUrl;
}

export async function searchPlaces(
  query: string,
  maxResults = 5,
  bias?: { lat: number; lng: number }
): Promise<DelhiPlace[]> {
  const q = query.trim();
  if (!q) return [];

  const params = new URLSearchParams({ q });
  if (bias) {
    params.set('lat', String(bias.lat));
    params.set('lng', String(bias.lng));
  }

  const res = await fetch(`${backendBaseUrl}/api/places?${params.toString()}`, {
    method: 'GET',
    headers: { Accept: 'application/json' }
  });

  if (!res.ok) {
    throw new Error(`Places search failed with HTTP ${res.status}`);
  }

  const payload = (await res.json()) as {
    value?: Array<{
      id: string;
      name: string;
      area?: string;
      fullAddress?: string;
      category?: string;
      placeType?: string;
      lat: number;
      lng: number;
      stationId?: string;
      stationHint?: { id?: string; stationId?: string };
    }>;
  };

  const list = Array.isArray(payload.value) ? payload.value : [];
  return list.slice(0, maxResults).map((item) => ({
    id: item.id,
    name: item.name,
    area: item.area ?? item.fullAddress ?? 'India',
    category: item.category ?? item.placeType ?? 'Place',
    lat: item.lat,
    lng: item.lng,
    stationId: item.stationId ?? item.stationHint?.stationId ?? item.stationHint?.id
  }));
}

export async function reverseGeocodePlace(
  lat: number,
  lng: number
): Promise<DelhiPlace | null> {
  const params = new URLSearchParams({
    lat: String(lat),
    lng: String(lng)
  });

  const res = await fetch(`${backendBaseUrl}/api/places/reverse?${params.toString()}`, {
    method: 'GET',
    headers: { Accept: 'application/json' }
  });

  if (res.status === 404) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`Reverse geocoding failed with HTTP ${res.status}`);
  }

  const payload = (await res.json()) as {
    value?: {
      id: string;
      name: string;
      area?: string;
      fullAddress?: string;
      category?: string;
      placeType?: string;
      lat: number;
      lng: number;
      stationId?: string;
      stationHint?: { id?: string; stationId?: string };
    } | null;
  };

  const v = payload.value;
  if (!v) return null;

  return {
    id: v.id,
    name: v.name,
    area: v.area ?? v.fullAddress ?? 'India',
    category: v.category ?? v.placeType ?? 'Place',
    lat: v.lat,
    lng: v.lng,
    stationId: v.stationId ?? v.stationHint?.stationId ?? v.stationHint?.id
  };
}
