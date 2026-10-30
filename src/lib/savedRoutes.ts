import { DelhiPlace } from '../data/places';
import { VehicleMode } from './types';

export interface SavedRoute {
  id: string;
  start: DelhiPlace;
  end: DelhiPlace;
  vehicle: VehicleMode;
  createdAt: number;
}

const STORAGE_KEY = 'commutebuddy_saved_routes_v1';

export function buildSavedRouteId(
  start: DelhiPlace,
  end: DelhiPlace,
  vehicle: VehicleMode
): string {
  return `${start.id}_${end.id}_${vehicle.toLowerCase()}`;
}

export function getSavedRoutes(): SavedRoute[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function saveRouteToStorage(
  start: DelhiPlace,
  end: DelhiPlace,
  vehicle: VehicleMode
): SavedRoute[] {
  const current = getSavedRoutes();
  const id = buildSavedRouteId(start, end, vehicle);
  const exists = current.some((item) => item.id === id);
  const next = exists
    ? current.filter((item) => item.id !== id)
    : [{ id, start, end, vehicle, createdAt: Date.now() }, ...current];
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  return next;
}

export function removeSavedRouteFromStorage(id: string): SavedRoute[] {
  const next = getSavedRoutes().filter((item) => item.id !== id);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  return next;
}

export function restoreSavedRouteToStorage(route: SavedRoute, index = 0): SavedRoute[] {
  const current = getSavedRoutes().filter((item) => item.id !== route.id);
  const clampedIndex = Math.max(0, Math.min(index, current.length));
  current.splice(clampedIndex, 0, route);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
  return current;
}
