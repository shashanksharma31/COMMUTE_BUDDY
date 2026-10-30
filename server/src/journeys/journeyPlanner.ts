import { FareLookupResult, OperatorFareStore } from '../ingestion/fareStore.js';
import { DayType, GtfsIngestionStore } from '../ingestion/gtfsFetcher.js';
import { StaticTrainScheduleStore } from '../ingestion/trainScheduleLoader.js';
import { RouteCoordinateInput } from '../providers/transit/types.js';
import { haversineMeters } from '../stations/h3Index.js';
import { CanonicalStation, StationEntityResolver } from '../stations/resolver.js';
import { createEnvelope, DataEnvelope, DataStatus } from '../types/envelope.js';
import { VerificationEngine } from '../verification/verificationEngine.js';
import { rateCardCalculator, RoadRateCardCalculator } from './rateCardCalculator.js';

export type JourneyLegMode =
  | 'walk'
  | 'auto'
  | 'e_rickshaw'
  | 'bike'
  | 'car'
  | 'metro'
  | 'rrts'
  | 'train'
  | 'bus';

export type JourneyLabel =
  | 'Fastest'
  | 'Cheapest'
  | 'Fewest transfers'
  | 'Least walking'
  | 'Most verified';

export interface JourneyLeg {
  legId: string;
  mode: JourneyLegMode;
  instruction: string;
  fromName: string;
  toName: string;
  fromStationId?: string;
  toStationId?: string;
  routeId?: string;
  lineName?: string;
  trainNumber?: string;
  operatorCode?: string;
  durationSeconds: DataEnvelope<number>;
  distanceMeters: number;
  transferBufferSeconds: number;
  fare: DataEnvelope<FareLookupResult & { isEstimate?: boolean }>;
  departureTimeIst?: string;
  arrivalTimeIst?: string;
  path: Array<[number, number]>;
}

export interface JourneyTotalFare {
  amountInr: number | null;
  currency: 'INR';
  hasEstimatedLeg: boolean;
  hasUnavailableLeg: boolean;
  isEstimate: boolean;
  message: string;
}

export interface MultiModalJourneyOption {
  id: string;
  label: JourneyLabel;
  tags: JourneyLabel[];
  legs: JourneyLeg[];
  totalDurationSeconds: DataEnvelope<number>;
  totalDistanceMeters: number;
  totalWalkMeters: number;
  transfers: number;
  totalFare: DataEnvelope<JourneyTotalFare>;
  lowestConfidence: number;
  overallStatus: DataStatus;
  recommendedLeaveByIst?: string;
  estimatedArrivalIst?: string;
  flags: string[];
}

export interface MultiModalJourneyResponse {
  status: 'ok' | 'no_data' | 'error';
  message: string;
  journeys: MultiModalJourneyOption[];
}

// Transfer buffer constants (in seconds)
export const SAME_COMPLEX_TRANSFER_BUFFER_SECONDS = 300; // 5 min (within 3-8 min rule)
export const CROSS_OPERATOR_HUB_BUFFER_SECONDS = 600; // 10 min (within 8-15 min rule)

const MAX_WALK_ACCESS_METERS = 1500;
const MAX_AUTO_ACCESS_METERS = 5000;
const WALK_SPEED_MPS = 1.25; // 4.5 km/h
const AUTO_SPEED_MPS = 5.5; // ~20 km/h urban last-mile
const AUTO_PICKUP_WAIT_SECONDS = 120;

interface TransitLegSegment {
  mode: 'metro' | 'rrts' | 'train';
  routeId: string;
  lineName: string;
  operatorCode: string;
  trainNumber?: string;
  fromStation: CanonicalStation;
  toStation: CanonicalStation;
  durationSeconds: number;
  distanceKm: number;
  headwayWaitSeconds: number;
  transferBufferBeforeSeconds: number;
  hubWalkBeforeMeters: number;
  hubWalkBeforeSeconds: number;
  path: Array<[number, number]>;
}

function parseHhMmToMinutes(timeStr: string): number | null {
  const trimmed = timeStr.trim();
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(trimmed);
  if (match) {
    return Number(match[1]) * 60 + Number(match[2]);
  }
  const dt = new Date(trimmed);
  if (!Number.isNaN(dt.getTime())) {
    const utcMin = dt.getUTCHours() * 60 + dt.getUTCMinutes();
    return (utcMin + 330) % 1440;
  }
  return null;
}

function formatMinutesToHhMm(totalMinutes: number): string {
  const norm = ((Math.round(totalMinutes) % 1440) + 1440) % 1440;
  const hh = String(Math.floor(norm / 60)).padStart(2, '0');
  const mm = String(norm % 60).padStart(2, '0');
  return `${hh}:${mm}`;
}

export class MultiModalJourneyPlanner {
  constructor(
    private readonly gtfs: GtfsIngestionStore,
    private readonly fares: OperatorFareStore,
    private readonly resolver: StationEntityResolver,
    private readonly railStore: StaticTrainScheduleStore,
    private readonly verifier: VerificationEngine,
    private readonly rates: RoadRateCardCalculator = rateCardCalculator
  ) {}

  planJourneys(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
    departureTime?: string;
    reachByTime?: string;
    dayType?: DayType;
    cityCode?: string;
    referenceDate?: Date;
  }): MultiModalJourneyResponse {
    const refDate = params.referenceDate ?? new Date();
    const dayType: DayType = params.dayType ?? 'weekday';

    // 1. Identify candidate entry and exit stations within <= 5 km
    const entryStations = this.findNearbyTransitStations(params.start);
    const exitStations = this.findNearbyTransitStations(params.end);

    if (entryStations.length === 0 || exitStations.length === 0) {
      return {
        status: 'no_data',
        message: 'no transit data for this area',
        journeys: []
      };
    }

    const rawCandidates: MultiModalJourneyOption[] = [];

    for (const entry of entryStations) {
      for (const exit of exitStations) {
        if (entry.station.id === exit.station.id) continue;

        const transitChains = this.findMultiModalTransitChains(
          entry.station,
          exit.station
        );

        for (const chain of transitChains) {
          const accessOptions = this.buildAccessOrEgressLegs({
            isAccess: true,
            coord: params.start,
            station: entry.station,
            distanceMeters: entry.distanceMeters,
            cityCode: params.cityCode ?? entry.station.cityCode
          });

          const egressOptions = this.buildAccessOrEgressLegs({
            isAccess: false,
            coord: params.end,
            station: exit.station,
            distanceMeters: exit.distanceMeters,
            cityCode: params.cityCode ?? exit.station.cityCode
          });

          for (const accessLeg of accessOptions) {
            for (const egressLeg of egressOptions) {
              const candidate = this.assembleJourneyCandidate({
                accessLeg,
                transitSegments: chain,
                egressLeg,
                departureTime: params.departureTime,
                reachByTime: params.reachByTime,
                dayType,
                referenceDate: refDate
              });

              if (candidate) {
                rawCandidates.push(candidate);
              }
            }
          }
        }
      }
    }

    if (rawCandidates.length === 0) {
      return {
        status: 'no_data',
        message: 'no transit data for this area',
        journeys: []
      };
    }

    // Prune dominated candidates via Pareto frontier and label the top options
    const paretoOptions = this.pruneAndLabelPareto(rawCandidates);

    return {
      status: 'ok',
      message: 'ok',
      journeys: paretoOptions
    };
  }

  private findNearbyTransitStations(
    coord: RouteCoordinateInput
  ): Array<{ station: CanonicalStation; distanceMeters: number }> {
    if (coord.stationId) {
      const exact = this.resolver.getStationById(coord.stationId);
      if (exact) {
        const d = haversineMeters(coord.lat, coord.lng, exact.lat, exact.lng);
        const results: Array<{ station: CanonicalStation; distanceMeters: number }> = [
          { station: exact, distanceMeters: d <= 150 ? 0 : d }
        ];
        // Also include hub-linked stations at the same interchange complex
        for (const link of this.resolver.getHubWalkingLinks(exact.id)) {
          results.push({
            station: link.toStation,
            distanceMeters: link.walkDistanceMeters
          });
        }
        return results;
      }
    }

    const matches: Array<{ station: CanonicalStation; distanceMeters: number }> = [];
    for (const stn of this.resolver.getAllStations()) {
      if (stn.stationType === 'bus' && !stn.isVerified) continue;
      const dist = haversineMeters(coord.lat, coord.lng, stn.lat, stn.lng);
      if (dist <= MAX_AUTO_ACCESS_METERS) {
        matches.push({ station: stn, distanceMeters: dist });
      }
    }

    matches.sort((a, b) => a.distanceMeters - b.distanceMeters);
    return matches.slice(0, 4);
  }

  private buildAccessOrEgressLegs(params: {
    isAccess: boolean;
    coord: RouteCoordinateInput;
    station: CanonicalStation;
    distanceMeters: number;
    cityCode: string;
  }): Array<JourneyLeg | null> {
    const dist = params.distanceMeters;
    const fromName = params.isAccess
      ? params.coord.name ?? 'Origin'
      : params.station.canonicalName;
    const toName = params.isAccess
      ? params.station.canonicalName
      : params.coord.name ?? 'Destination';
    const pathCoords: Array<[number, number]> = params.isAccess
      ? [
          [params.coord.lat, params.coord.lng],
          [params.station.lat, params.station.lng]
        ]
      : [
          [params.station.lat, params.station.lng],
          [params.coord.lat, params.coord.lng]
        ];

    // If coordinate is already at the station (<= 80m), no separate access/egress leg needed
    if (dist <= 80) {
      return [null];
    }

    const legs: Array<JourneyLeg | null> = [];

    // 1. Walk option for <= 1,500m
    if (dist <= MAX_WALK_ACCESS_METERS) {
      const walkSec = Math.max(60, Math.round(dist / WALK_SPEED_MPS));
      legs.push({
        legId: params.isAccess ? 'leg_access_walk' : 'leg_egress_walk',
        mode: 'walk',
        instruction: `Walk ${dist} m from ${fromName} to ${toName}`,
        fromName,
        toName,
        ...(params.isAccess
          ? { toStationId: params.station.id }
          : { fromStationId: params.station.id }),
        durationSeconds: createEnvelope<number>({
          value: walkSec,
          status: 'scheduled',
          sources: ['osm_overpass'],
          primarySourceWeight: 0.9
        }),
        distanceMeters: dist,
        transferBufferSeconds: 0,
        fare: createEnvelope<FareLookupResult & { isEstimate?: boolean }>({
          value: {
            fareInr: 0,
            currency: 'INR',
            matchedBy: 'operator_slab',
            message: '₹0',
            operatorCode: 'WALK',
            operatorDeepLink: '',
            source_url: 'https://www.openstreetmap.org/',
            effective_from: '2025-01-01',
            isStaleOver90Days: false,
            isEstimate: false
          },
          status: 'scheduled',
          sources: ['osm_overpass'],
          primarySourceWeight: 0.95
        }),
        path: pathCoords
      });
    }

    // 2. Last-mile Auto / E-rickshaw option for 450m < dist <= 5,000m (state tariff meter card)
    if (dist > 450 && dist <= MAX_AUTO_ACCESS_METERS) {
      const rideSec =
        AUTO_PICKUP_WAIT_SECONDS + Math.max(120, Math.round(dist / AUTO_SPEED_MPS));
      const mode: 'e_rickshaw' | 'auto' = dist <= 2000 ? 'e_rickshaw' : 'auto';
      const estFare = this.rates.calculateEstimatedFare({
        mode,
        distanceMeters: dist,
        durationSeconds: rideSec,
        cityCode: params.cityCode
      });

      legs.push({
        legId: params.isAccess ? `leg_access_${mode}` : `leg_egress_${mode}`,
        mode,
        instruction: `${mode === 'e_rickshaw' ? 'E-Rickshaw' : 'Auto'} (${(dist / 1000).toFixed(1)} km) from ${fromName} to ${toName}`,
        fromName,
        toName,
        ...(params.isAccess
          ? { toStationId: params.station.id }
          : { fromStationId: params.station.id }),
        durationSeconds: createEnvelope<number>({
          value: rideSec,
          status: 'estimate',
          sources: estFare.sources,
          primarySourceWeight: 0.8
        }),
        distanceMeters: dist,
        transferBufferSeconds: 0,
        fare: estFare,
        path: pathCoords
      });
    }

    return legs;
  }

  // Multi-operator Dijkstra/BFS across GTFS Metro, RRTS YAML, Indian Railways static schedules, and Hub Walking Links
  private findMultiModalTransitChains(
    startStn: CanonicalStation,
    endStn: CanonicalStation
  ): TransitLegSegment[][] {
    interface QueueItem {
      station: CanonicalStation;
      segments: TransitLegSegment[];
      visitedStations: Set<string>;
      pendingHubWalk?: {
        walkMeters: number;
        walkSeconds: number;
      };
    }

    const results: TransitLegSegment[][] = [];
    const queue: QueueItem[] = [
      {
        station: startStn,
        segments: [],
        visitedStations: new Set([startStn.id])
      }
    ];

    while (queue.length > 0 && results.length < 6) {
      const curr = queue.shift()!;

      if (curr.station.id === endStn.id && curr.segments.length > 0) {
        results.push(curr.segments);
        continue;
      }

      // Max 3 transit segments (which means max 2 transit transfers)
      if (curr.segments.length >= 3) {
        continue;
      }

      // 1. Expand GTFS Metro & RRTS edges from curr.station
      for (const edge of this.gtfs.getEdgesFromStation(curr.station.id)) {
        if (curr.visitedStations.has(edge.toStationId)) continue;
        const nextStn = this.resolver.getStationById(edge.toStationId);
        const routeDef = this.gtfs.getRouteById(edge.routeId);
        if (!nextStn || !routeDef) continue;

        const nextVisited = new Set(curr.visitedStations);
        nextVisited.add(nextStn.id);

        const lastSeg = curr.segments[curr.segments.length - 1];
        if (lastSeg && lastSeg.routeId === edge.routeId && !curr.pendingHubWalk) {
          // Extend current segment on the same line
          const merged: TransitLegSegment = {
            ...lastSeg,
            toStation: nextStn,
            durationSeconds: lastSeg.durationSeconds + edge.scheduledTravelSeconds,
            distanceKm: Math.round((lastSeg.distanceKm + edge.distanceKm) * 100) / 100,
            path: [...lastSeg.path, [nextStn.lat, nextStn.lng]]
          };
          queue.push({
            station: nextStn,
            segments: [...curr.segments.slice(0, -1), merged],
            visitedStations: nextVisited
          });
        } else {
          // New transit leg (either first leg, same-complex line transfer, or post-hub-walk transfer)
          if (curr.segments.length >= 3) continue;
          const isFirstTransit = curr.segments.length === 0;
          const isHubTransfer = Boolean(curr.pendingHubWalk);
          const transferBuffer = isFirstTransit
            ? 0
            : isHubTransfer
            ? CROSS_OPERATOR_HUB_BUFFER_SECONDS
            : SAME_COMPLEX_TRANSFER_BUFFER_SECONDS;

          const newSeg: TransitLegSegment = {
            mode: routeDef.mode === 'rrts' ? 'rrts' : 'metro',
            routeId: edge.routeId,
            lineName: routeDef.lineName,
            operatorCode: routeDef.operatorCode,
            fromStation: curr.station,
            toStation: nextStn,
            durationSeconds: edge.scheduledTravelSeconds,
            distanceKm: edge.distanceKm,
            headwayWaitSeconds: Math.round(edge.headwayPeakSeconds / 2),
            transferBufferBeforeSeconds: transferBuffer,
            hubWalkBeforeMeters: curr.pendingHubWalk?.walkMeters ?? 0,
            hubWalkBeforeSeconds: curr.pendingHubWalk?.walkSeconds ?? 0,
            path: [
              [curr.station.lat, curr.station.lng],
              [nextStn.lat, nextStn.lng]
            ]
          };

          queue.push({
            station: nextStn,
            segments: [...curr.segments, newSeg],
            visitedStations: nextVisited
          });
        }
      }

      // 2. Expand Indian Railways static schedules from curr.station
      if (curr.station.stationType === 'rail') {
        for (const otherStn of this.resolver.getAllStations()) {
          if (otherStn.stationType !== 'rail' || curr.visitedStations.has(otherStn.id)) {
            continue;
          }
          const schedules = this.railStore.findSchedulesBetween(
            curr.station.id,
            otherStn.id
          );
          if (schedules.length === 0) continue;

          const fastestTrain = schedules[0];
          const distKm =
            Math.round(
              (haversineMeters(
                curr.station.lat,
                curr.station.lng,
                otherStn.lat,
                otherStn.lng
              ) /
                1000) *
                100
            ) / 100;

          const isFirstTransit = curr.segments.length === 0;
          const transferBuffer = isFirstTransit
            ? 0
            : CROSS_OPERATOR_HUB_BUFFER_SECONDS;

          const railSeg: TransitLegSegment = {
            mode: 'train',
            routeId: `IR_${fastestTrain.trainNumber}`,
            lineName: `${fastestTrain.trainNumber} ${fastestTrain.trainName}`,
            operatorCode: 'IR',
            trainNumber: fastestTrain.trainNumber,
            fromStation: curr.station,
            toStation: otherStn,
            durationSeconds: fastestTrain.travelDurationSeconds,
            distanceKm: distKm,
            headwayWaitSeconds: 300,
            transferBufferBeforeSeconds: transferBuffer,
            hubWalkBeforeMeters: curr.pendingHubWalk?.walkMeters ?? 0,
            hubWalkBeforeSeconds: curr.pendingHubWalk?.walkSeconds ?? 0,
            path: [
              [curr.station.lat, curr.station.lng],
              [otherStn.lat, otherStn.lng]
            ]
          };

          const nextVisited = new Set(curr.visitedStations);
          nextVisited.add(otherStn.id);

          queue.push({
            station: otherStn,
            segments: [...curr.segments, railSeg],
            visitedStations: nextVisited
          });
        }
      }

      // 3. Expand Interchange Hub Walking Links (<= 400m) when switching between operators/modes
      if (!curr.pendingHubWalk && curr.segments.length > 0) {
        for (const hubLink of this.resolver.getHubWalkingLinks(curr.station.id)) {
          if (curr.visitedStations.has(hubLink.toStationId)) continue;
          const nextVisited = new Set(curr.visitedStations);
          nextVisited.add(hubLink.toStationId);

          queue.push({
            station: hubLink.toStation,
            segments: curr.segments,
            visitedStations: nextVisited,
            pendingHubWalk: {
              walkMeters: hubLink.walkDistanceMeters,
              walkSeconds: hubLink.walkDurationSeconds
            }
          });
        }
      }
    }

    return results;
  }

  private assembleJourneyCandidate(params: {
    accessLeg: JourneyLeg | null;
    transitSegments: TransitLegSegment[];
    egressLeg: JourneyLeg | null;
    departureTime?: string;
    reachByTime?: string;
    dayType: DayType;
    referenceDate: Date;
  }): MultiModalJourneyOption | null {
    const legs: JourneyLeg[] = [];
    let totalWalkMeters = 0;

    if (params.accessLeg) {
      legs.push({
        ...params.accessLeg,
        legId: `leg_${legs.length + 1}`
      });
      if (params.accessLeg.mode === 'walk') {
        totalWalkMeters += params.accessLeg.distanceMeters;
      }
    }

    for (const seg of params.transitSegments) {
      totalWalkMeters += seg.hubWalkBeforeMeters;

      const legTotalSeconds =
        seg.durationSeconds +
        seg.headwayWaitSeconds +
        seg.transferBufferBeforeSeconds +
        seg.hubWalkBeforeSeconds;

      const rawFareEnv = this.fares.lookupExactTransitFare({
        operatorCode: seg.operatorCode,
        fromStationId: seg.fromStation.id,
        toStationId: seg.toStation.id,
        trackDistanceKm: seg.distanceKm,
        dayType: params.dayType,
        referenceDate: params.referenceDate
      });

      const verifiedFare = this.verifier.verifyFare({
        entityKey: `${seg.operatorCode}:${seg.fromStation.id}->${seg.toStation.id}`,
        primaryEnvelope: rawFareEnv,
        referenceDate: params.referenceDate
      });

      const sourceName =
        seg.mode === 'rrts' || seg.mode === 'train'
          ? 'operator_official'
          : 'operator_gtfs';

      legs.push({
        legId: `leg_${legs.length + 1}`,
        mode: seg.mode,
        instruction: `${seg.lineName}: ${seg.fromStation.canonicalName} to ${seg.toStation.canonicalName}`,
        fromName: seg.fromStation.canonicalName,
        toName: seg.toStation.canonicalName,
        fromStationId: seg.fromStation.id,
        toStationId: seg.toStation.id,
        routeId: seg.routeId,
        lineName: seg.lineName,
        trainNumber: seg.trainNumber,
        operatorCode: seg.operatorCode,
        durationSeconds: createEnvelope<number>({
          value: legTotalSeconds,
          status: 'scheduled',
          sources: [sourceName],
          primarySourceWeight: seg.mode === 'rrts' ? 1.0 : 0.95
        }),
        distanceMeters: Math.round(seg.distanceKm * 1000),
        transferBufferSeconds: seg.transferBufferBeforeSeconds,
        fare: verifiedFare.envelope,
        path: seg.path
      });
    }

    if (params.egressLeg) {
      legs.push({
        ...params.egressLeg,
        legId: `leg_${legs.length + 1}`
      });
      if (params.egressLeg.mode === 'walk') {
        totalWalkMeters += params.egressLeg.distanceMeters;
      }
    }

    const transfers = Math.max(0, params.transitSegments.length - 1);

    // Enforce hard limits: max 4 legs, max 2 transfers
    if (legs.length > 4 || transfers > 2) {
      return null;
    }

    const totalDurationSec = legs.reduce(
      (sum, l) => sum + (l.durationSeconds.value ?? 0),
      0
    );
    const totalDistanceMeters = legs.reduce((sum, l) => sum + l.distanceMeters, 0);

    // Solve leg timestamps (forward from departureTime OR backward from reachByTime)
    let startMinutesIst: number | null = null;
    if (params.reachByTime) {
      const reachMin = parseHhMmToMinutes(params.reachByTime);
      if (reachMin !== null) {
        startMinutesIst = reachMin - Math.ceil(totalDurationSec / 60);
      }
    } else if (params.departureTime) {
      startMinutesIst = parseHhMmToMinutes(params.departureTime);
    }

    let recommendedLeaveByIst: string | undefined;
    let estimatedArrivalIst: string | undefined;
    const flags = new Set<string>();
    let anyOutsideOperatingHours = false;

    if (startMinutesIst !== null) {
      recommendedLeaveByIst = formatMinutesToHhMm(startMinutesIst);
      let cursorSeconds = 0;

      for (const leg of legs) {
        const depMin = startMinutesIst + Math.floor(cursorSeconds / 60);
        const legDurSec = leg.durationSeconds.value ?? 0;
        const arrMin = startMinutesIst + Math.ceil((cursorSeconds + legDurSec) / 60);
        leg.departureTimeIst = formatMinutesToHhMm(depMin);
        leg.arrivalTimeIst = formatMinutesToHhMm(arrMin);

        if ((leg.mode === 'metro' || leg.mode === 'rrts') && leg.routeId && leg.fromStationId) {
          const ohCheck = this.verifier.verifyOperatingHours({
            steps: [
              {
                mode: leg.mode,
                instruction: leg.instruction,
                durationSeconds: legDurSec,
                distanceMeters: leg.distanceMeters,
                fromStationId: leg.fromStationId,
                toStationId: leg.toStationId,
                routeId: leg.routeId
              }
            ],
            departureTime: leg.departureTimeIst,
            dayType: params.dayType
          });
          if (!ohCheck.withinOperatingHours) {
            anyOutsideOperatingHours = true;
            flags.add('outside_operating_hours');
          }
        }

        cursorSeconds += legDurSec;
      }

      estimatedArrivalIst = formatMinutesToHhMm(
        startMinutesIst + Math.ceil(totalDurationSec / 60)
      );
    }

    // Aggregate total fare across legs
    let totalFareInr = 0;
    let hasEstimatedLeg = false;
    let hasUnavailableLeg = false;
    const fareSources = new Set<string>();

    for (const leg of legs) {
      for (const s of leg.fare.sources) fareSources.add(s);
      if (leg.fare.status === 'unavailable' || leg.fare.value?.fareInr === null) {
        hasUnavailableLeg = true;
      } else {
        totalFareInr += leg.fare.value?.fareInr ?? 0;
        if (leg.fare.status === 'estimate' || leg.fare.value?.isEstimate) {
          hasEstimatedLeg = true;
        }
        if (leg.fare.status === 'stale') {
          flags.add('fare_table_older_than_90_days');
        }
      }
    }

    // Verify all stations along the journey
    const confidences: number[] = [];
    for (const leg of legs) {
      confidences.push(leg.durationSeconds.confidence);
      if (leg.fromStationId) {
        const stCheck = this.verifier.verifyStationIdentity(leg.fromStationId);
        confidences.push(stCheck.confidence);
        for (const f of stCheck.flags) flags.add(f);
      }
      if (leg.toStationId) {
        const stCheck = this.verifier.verifyStationIdentity(leg.toStationId);
        confidences.push(stCheck.confidence);
        for (const f of stCheck.flags) flags.add(f);
      }
    }

    const lowestConfidence =
      anyOutsideOperatingHours
        ? 0
        : confidences.length > 0
        ? Math.round(Math.min(...confidences) * 1000) / 1000
        : 0.5;

    const overallStatus: DataStatus = anyOutsideOperatingHours
      ? 'unavailable'
      : flags.has('single_source_station')
      ? 'unverified'
      : flags.has('fare_table_older_than_90_days')
      ? 'stale'
      : hasEstimatedLeg
      ? 'estimate'
      : 'scheduled';

    const totalDurationEnv = createEnvelope<number>({
      value: totalDurationSec,
      status: anyOutsideOperatingHours ? 'unavailable' : 'scheduled',
      sources: Array.from(
        new Set(legs.flatMap((l) => l.durationSeconds.sources))
      ),
      primarySourceWeight: anyOutsideOperatingHours ? 0 : 0.92
    });

    const totalFareEnv = createEnvelope<JourneyTotalFare>({
      value: {
        amountInr: hasUnavailableLeg ? null : totalFareInr,
        currency: 'INR',
        hasEstimatedLeg,
        hasUnavailableLeg,
        isEstimate: hasEstimatedLeg,
        message: hasUnavailableLeg
          ? 'fare unavailable'
          : hasEstimatedLeg
          ? `₹${totalFareInr} (Estimated)`
          : `₹${totalFareInr}`
      },
      status: hasUnavailableLeg
        ? 'unavailable'
        : hasEstimatedLeg
        ? 'estimate'
        : flags.has('fare_table_older_than_90_days')
        ? 'stale'
        : 'scheduled',
      sources: Array.from(fareSources),
      primarySourceWeight: hasUnavailableLeg ? 0 : hasEstimatedLeg ? 0.85 : 0.98
    });

    const modeSummary = legs.map((l) => l.mode).join('_');
    const stnSummary = params.transitSegments
      .map((s) => `${s.fromStation.id}-${s.toStation.id}`)
      .join('_');

    return {
      id: `journey_${modeSummary}_${stnSummary}`,
      label: 'Fastest',
      tags: [],
      legs,
      totalDurationSeconds: totalDurationEnv,
      totalDistanceMeters,
      totalWalkMeters,
      transfers,
      totalFare: totalFareEnv,
      lowestConfidence,
      overallStatus,
      ...(recommendedLeaveByIst ? { recommendedLeaveByIst } : {}),
      ...(estimatedArrivalIst ? { estimatedArrivalIst } : {}),
      flags: Array.from(flags)
    };
  }

  private pruneAndLabelPareto(
    candidates: MultiModalJourneyOption[]
  ): MultiModalJourneyOption[] {
    // Deduplicate by id
    const unique = new Map<string, MultiModalJourneyOption>();
    for (const c of candidates) {
      if (!unique.has(c.id)) {
        unique.set(c.id, c);
      }
    }
    const list = Array.from(unique.values());

    // Filter out candidates strictly dominated on all 4 objectives:
    // (durationSeconds, fareInr, transfers, -lowestConfidence)
    const nonDominated = list.filter((a) => {
      const aDur = a.totalDurationSeconds.value ?? Infinity;
      const aFare = a.totalFare.value?.amountInr ?? Infinity;
      const aTrans = a.transfers;
      const aConf = a.lowestConfidence;

      return !list.some((b) => {
        if (a.id === b.id) return false;
        const bDur = b.totalDurationSeconds.value ?? Infinity;
        const bFare = b.totalFare.value?.amountInr ?? Infinity;
        const bTrans = b.transfers;
        const bConf = b.lowestConfidence;

        const noWorseInAll =
          bDur <= aDur &&
          bFare <= aFare &&
          bTrans <= aTrans &&
          bConf >= aConf;
        const strictlyBetterInOne =
          bDur < aDur ||
          bFare < aFare ||
          bTrans < aTrans ||
          bConf > aConf;

        return noWorseInAll && strictlyBetterInOne;
      });
    });

    const pool = nonDominated.length > 0 ? nonDominated : list;
    pool.sort(
      (a, b) =>
        (a.totalDurationSeconds.value ?? Infinity) -
        (b.totalDurationSeconds.value ?? Infinity)
    );

    const selected = pool.slice(0, 4);
    if (selected.length === 0) return [];

    // Identify extrema for clear user labels
    const fastest = [...selected].sort(
      (a, b) =>
        (a.totalDurationSeconds.value ?? Infinity) -
        (b.totalDurationSeconds.value ?? Infinity)
    )[0];
    const cheapest = [...selected].sort(
      (a, b) =>
        (a.totalFare.value?.amountInr ?? Infinity) -
        (b.totalFare.value?.amountInr ?? Infinity)
    )[0];
    const fewestTransfers = [...selected].sort(
      (a, b) => a.transfers - b.transfers
    )[0];
    const mostVerified = [...selected].sort(
      (a, b) => b.lowestConfidence - a.lowestConfidence
    )[0];

    for (const opt of selected) {
      const tags: JourneyLabel[] = [];
      if (opt.id === fastest.id) tags.push('Fastest');
      if (opt.id === cheapest.id) tags.push('Cheapest');
      if (opt.id === fewestTransfers.id) tags.push('Fewest transfers');
      if (opt.id === mostVerified.id) tags.push('Most verified');

      opt.tags = tags.length > 0 ? tags : ['Fastest'];
      opt.label =
        tags.includes('Fastest')
          ? 'Fastest'
          : tags.includes('Cheapest')
          ? 'Cheapest'
          : tags.includes('Fewest transfers')
          ? 'Fewest transfers'
          : tags.includes('Most verified')
          ? 'Most verified'
          : 'Least walking';
    }

    return selected;
  }
}
