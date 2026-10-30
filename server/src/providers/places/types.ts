export type PlaceCategory =
  | 'metro_station'
  | 'railway_station'
  | 'rrts_station'
  | 'bus_stop'
  | 'airport'
  | 'poi'
  | 'locality'
  | 'address';

export interface StationHint {
  isStation: true;
  stationMode: 'metro' | 'rail' | 'rrts' | 'bus';
  osmType?: 'N' | 'W' | 'R';
  osmId?: number;
  rawName: string;
  city?: string;
  state?: string;
}

export interface PlaceSuggestion {
  id: string;
  name: string;
  fullAddress: string;
  lat: number;
  lng: number;
  placeType: PlaceCategory;
  countryCode: 'IN';
  stationHint?: StationHint;
}
