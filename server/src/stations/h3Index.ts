// ~100m resolution spatial cell indexer (H3 res-10 equivalent ~100m grid) and Haversine distance.
const CELL_SIZE_DEG = 0.0009; // ~100 meters in latitude/longitude

export function latLngToH3Cell100m(lat: number, lng: number): string {
  const latBucket = Math.round(lat / CELL_SIZE_DEG);
  const lngBucket = Math.round(lng / CELL_SIZE_DEG);
  return `h3r10_${latBucket}_${lngBucket}`;
}

export function getNeighborCells100m(lat: number, lng: number, ringRadius = 1): Set<string> {
  const centerLat = Math.round(lat / CELL_SIZE_DEG);
  const centerLng = Math.round(lng / CELL_SIZE_DEG);
  const cells = new Set<string>();
  for (let dLat = -ringRadius; dLat <= ringRadius; dLat++) {
    for (let dLng = -ringRadius; dLng <= ringRadius; dLng++) {
      cells.add(`h3r10_${centerLat + dLat}_${centerLng + dLng}`);
    }
  }
  return cells;
}

export function haversineMeters(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number
): number {
  const R = 6371000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return Math.round(R * c);
}
