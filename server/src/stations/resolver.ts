import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvelope, DataEnvelope } from '../types/envelope.js';
import { getNeighborCells100m, haversineMeters, latLngToH3Cell100m } from './h3Index.js';

export type StationMode = 'metro' | 'rail' | 'rrts' | 'bus';

export interface CanonicalStation {
  id: string;
  canonicalName: string;
  aliases: string[];
  cityCode: string;
  stationType: StationMode;
  lat: number;
  lng: number;
  h3Res10: string;
  gtfsStopIds: string[];
  irStationCode: string | null;
  osmNodeIds: number[];
  googlePlaceId: string | null;
  wikidataId: string | null;
  confirmingSources: string[];
  isVerified: boolean; // True only when confirmed by >= 2 independent sources
  source_url: string;
  effective_from: string;
}

export interface HubWalkingLink {
  hubId: string;
  fromStationId: string;
  toStationId: string;
  toStation: CanonicalStation;
  walkDistanceMeters: number;
  walkDurationSeconds: number;
  source_url: string;
  effective_from: string;
}

export interface StationCandidateMatch {
  station: CanonicalStation;
  score: number; // 0.0 to 1.0
  matchedBy: string;
  distanceMeters?: number;
}

export interface StationResolutionResult {
  resolution: 'resolved' | 'ambiguous' | 'not_found';
  station: CanonicalStation | null;
  candidates: StationCandidateMatch[];
  hubLinks: HubWalkingLink[];
}

interface RawStationRecord {
  id: string;
  canonicalName: string;
  aliases: string[];
  cityCode: string;
  stationType: StationMode;
  lat: number;
  lng: number;
  gtfsStopIds: string[];
  irStationCode: string | null;
  osmNodeIds: number[];
  googlePlaceId: string | null;
  wikidataId: string | null;
  confirmingSources: string[];
  source_url: string;
  effective_from: string;
}

interface RawHubLinkRecord {
  hubId: string;
  fromStationId: string;
  toStationId: string;
  walkDistanceMeters: number;
  walkDurationSeconds: number;
  source_url: string;
  effective_from: string;
}

interface StationsDatasetFile {
  stations: RawStationRecord[];
  hubLinks: RawHubLinkRecord[];
}

const STOP_WORDS = new Set([
  'metro',
  'station',
  'railway',
  'junction',
  'jn',
  'terminal',
  'terminus',
  'rrts',
  'halt',
  'isbt'
]);

function normalizeText(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function stripGenericStopWords(text: string): string {
  const tokens = normalizeText(text)
    .split(' ')
    .filter((t) => t.length > 0 && !STOP_WORDS.has(t));
  return tokens.join(' ');
}

function buildTrigrams(input: string): Set<string> {
  const padded = `  ${normalizeText(input)} `;
  const set = new Set<string>();
  for (let i = 0; i <= padded.length - 3; i++) {
    set.add(padded.slice(i, i + 3));
  }
  return set;
}

export function trigramSimilarity(a: string, b: string): number {
  const normA = normalizeText(a);
  const normB = normalizeText(b);
  if (!normA || !normB) return 0;
  if (normA === normB) return 1.0;

  const coreA = stripGenericStopWords(normA) || normA;
  const coreB = stripGenericStopWords(normB) || normB;
  if (coreA === coreB) return 0.96;

  const triA = buildTrigrams(coreA);
  const triB = buildTrigrams(coreB);
  if (triA.size === 0 || triB.size === 0) return 0;

  let intersection = 0;
  for (const t of triA) {
    if (triB.has(t)) intersection += 1;
  }
  const union = triA.size + triB.size - intersection;
  const jaccard = union === 0 ? 0 : intersection / union;

  // Substring containment boost for partial station names
  const containsBoost =
    coreA.includes(coreB) || coreB.includes(coreA) ? 0.22 : 0;

  return Math.min(0.95, Math.round((jaccard + containsBoost) * 1000) / 1000);
}

function loadDefaultDataset(): StationsDatasetFile {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [
    path.resolve(currentDir, '../../data/stations/india_canonical_stations.json'),
    path.resolve(process.cwd(), 'data/stations/india_canonical_stations.json'),
    path.resolve(process.cwd(), 'server/data/stations/india_canonical_stations.json')
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) {
      return JSON.parse(fs.readFileSync(p, 'utf8')) as StationsDatasetFile;
    }
  }
  throw new Error('Could not locate server/data/stations/india_canonical_stations.json');
}

export class StationEntityResolver {
  private readonly stations = new Map<string, CanonicalStation>();
  private readonly hubLinksByStation = new Map<string, HubWalkingLink[]>();

  constructor(dataset?: StationsDatasetFile) {
    const data = dataset ?? loadDefaultDataset();

    for (const raw of data.stations) {
      if (!raw.source_url || !raw.effective_from) {
        throw new Error(`Station ${raw.id} is missing source_url or effective_from`);
      }
      const uniqueSources = Array.from(new Set(raw.confirmingSources));
      const station: CanonicalStation = {
        ...raw,
        h3Res10: latLngToH3Cell100m(raw.lat, raw.lng),
        confirmingSources: uniqueSources,
        isVerified: uniqueSources.length >= 2
      };
      this.stations.set(station.id, station);
    }

    for (const link of data.hubLinks) {
      if (link.walkDistanceMeters > 400) {
        throw new Error(
          `Hub walking link ${link.fromStationId} -> ${link.toStationId} exceeds 400m limit (${link.walkDistanceMeters}m)`
        );
      }
      const fromStn = this.stations.get(link.fromStationId);
      const toStn = this.stations.get(link.toStationId);
      if (!fromStn || !toStn) continue;

      this.addDirectedHubLink(link, fromStn, toStn);
      this.addDirectedHubLink(
        {
          ...link,
          fromStationId: link.toStationId,
          toStationId: link.fromStationId
        },
        toStn,
        fromStn
      );
    }
  }

  private addDirectedHubLink(
    raw: RawHubLinkRecord,
    fromStn: CanonicalStation,
    toStn: CanonicalStation
  ): void {
    const list = this.hubLinksByStation.get(fromStn.id) ?? [];
    list.push({
      hubId: raw.hubId,
      fromStationId: fromStn.id,
      toStationId: toStn.id,
      toStation: toStn,
      walkDistanceMeters: raw.walkDistanceMeters,
      walkDurationSeconds: raw.walkDurationSeconds,
      source_url: raw.source_url,
      effective_from: raw.effective_from
    });
    this.hubLinksByStation.set(fromStn.id, list);
  }

  getStationById(id: string): CanonicalStation | null {
    return this.stations.get(id) ?? null;
  }

  getAllStations(): CanonicalStation[] {
    return Array.from(this.stations.values());
  }

  getHubWalkingLinks(stationId: string): HubWalkingLink[] {
    return this.hubLinksByStation.get(stationId) ?? [];
  }

  // Resolves a station by exact external IDs, fuzzy name/Hindi/regional alias, and ~100m H3 proximity.
  // Returns 'ambiguous' if the top two candidates score within 0.1 of each other.
  resolveStation(query: {
    id?: string;
    gtfsStopId?: string;
    irStationCode?: string;
    osmNodeId?: number;
    googlePlaceId?: string;
    wikidataId?: string;
    name?: string;
    lat?: number;
    lng?: number;
    cityCode?: string;
    stationType?: StationMode;
  }): DataEnvelope<StationResolutionResult> {
    // 1. Exact ID lookups
    for (const stn of this.stations.values()) {
      if (query.id && stn.id === query.id) {
        return this.buildResolvedEnvelope(stn, 1.0, 'canonical_id');
      }
      if (query.gtfsStopId && stn.gtfsStopIds.includes(query.gtfsStopId)) {
        return this.buildResolvedEnvelope(stn, 1.0, 'gtfs_stop_id');
      }
      if (
        query.irStationCode &&
        stn.irStationCode?.toUpperCase() === query.irStationCode.trim().toUpperCase()
      ) {
        return this.buildResolvedEnvelope(stn, 1.0, 'ir_station_code');
      }
      if (query.osmNodeId !== undefined && stn.osmNodeIds.includes(query.osmNodeId)) {
        return this.buildResolvedEnvelope(stn, 1.0, 'osm_node_id');
      }
      if (query.googlePlaceId && stn.googlePlaceId === query.googlePlaceId) {
        return this.buildResolvedEnvelope(stn, 1.0, 'google_place_id');
      }
      if (query.wikidataId && stn.wikidataId === query.wikidataId) {
        return this.buildResolvedEnvelope(stn, 1.0, 'wikidata_id');
      }
    }

    // 2. Fuzzy name / regional alias + H3 (~100m) spatial index scoring
    const targetCells =
      query.lat !== undefined && query.lng !== undefined
        ? getNeighborCells100m(query.lat, query.lng, 2)
        : null;

    const candidates: StationCandidateMatch[] = [];

    for (const stn of this.stations.values()) {
      if (query.cityCode && stn.cityCode.toUpperCase() !== query.cityCode.toUpperCase()) {
        continue;
      }
      if (query.stationType && stn.stationType !== query.stationType) {
        continue;
      }

      let nameScore = 0;
      let matchedBy = 'name';
      if (query.name && query.name.trim().length > 0) {
        const qName = query.name.trim();
        nameScore = trigramSimilarity(qName, stn.canonicalName);

        for (const alias of stn.aliases) {
          const aliasScore = trigramSimilarity(qName, alias);
          if (aliasScore > nameScore) {
            nameScore = aliasScore;
            matchedBy = `alias:${alias}`;
          }
        }
        if (
          stn.irStationCode &&
          stn.irStationCode.toLowerCase() === qName.toLowerCase()
        ) {
          nameScore = Math.max(nameScore, 0.98);
          matchedBy = 'ir_code_alias';
        }
      }

      let spatialScore = 0;
      let distanceMeters: number | undefined;
      if (query.lat !== undefined && query.lng !== undefined) {
        distanceMeters = haversineMeters(query.lat, query.lng, stn.lat, stn.lng);
        if (targetCells?.has(stn.h3Res10) || distanceMeters <= 120) {
          spatialScore = 1.0;
          matchedBy = query.name ? `${matchedBy}+h3_100m` : 'h3_100m';
        } else if (distanceMeters <= 400) {
          spatialScore = Math.max(0.4, 1 - distanceMeters / 500);
        } else if (distanceMeters <= 1500) {
          spatialScore = Math.max(0.1, 0.4 * (1 - distanceMeters / 1500));
        }
      }

      const combinedScore =
        query.name && query.lat !== undefined && query.lng !== undefined
          ? Math.round((nameScore * 0.7 + spatialScore * 0.3) * 1000) / 1000
          : query.name
          ? nameScore
          : spatialScore;

      if (combinedScore >= 0.35) {
        candidates.push({
          station: stn,
          score: combinedScore,
          matchedBy,
          ...(distanceMeters !== undefined ? { distanceMeters } : {})
        });
      }
    }

    candidates.sort((a, b) => b.score - a.score);

    if (candidates.length === 0) {
      return createEnvelope<StationResolutionResult>({
        value: {
          resolution: 'not_found',
          station: null,
          candidates: [],
          hubLinks: []
        },
        status: 'unavailable',
        sources: [],
        primarySourceWeight: 0
      });
    }

    // Ambiguity rule: if the top two candidates score within 0.1, return an ambiguity result
    if (candidates.length >= 2) {
      const top = candidates[0];
      const second = candidates[1];
      if (Math.abs(top.score - second.score) <= 0.1 + 1e-9) {
        const ambiguousCandidates = candidates.filter(
          (c) => top.score - c.score <= 0.1 + 1e-9
        );
        return createEnvelope<StationResolutionResult>({
          value: {
            resolution: 'ambiguous',
            station: null,
            candidates: ambiguousCandidates,
            hubLinks: []
          },
          status: 'unverified',
          sources: top.station.confirmingSources,
          primarySourceWeight: 0.5
        });
      }
    }

    const winner = candidates[0];
    return this.buildResolvedEnvelope(
      winner.station,
      winner.score,
      winner.matchedBy,
      candidates.slice(0, 5)
    );
  }

  private buildResolvedEnvelope(
    station: CanonicalStation,
    score: number,
    matchedBy: string,
    allCandidates?: StationCandidateMatch[]
  ): DataEnvelope<StationResolutionResult> {
    const status = station.isVerified ? 'scheduled' : 'unverified';
    const baseWeight = station.isVerified ? Math.min(0.95, 0.75 + score * 0.2) : 0.45;
    const hubLinks = this.getHubWalkingLinks(station.id);

    return createEnvelope<StationResolutionResult>({
      value: {
        resolution: 'resolved',
        station,
        candidates: allCandidates ?? [{ station, score, matchedBy }],
        hubLinks
      },
      status,
      sources: station.confirmingSources,
      primarySourceWeight: baseWeight
    });
  }
}

export const stationResolver = new StationEntityResolver();
