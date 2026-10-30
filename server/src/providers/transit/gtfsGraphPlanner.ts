import { OperatorFareStore } from '../../ingestion/fareStore.js';
import { GtfsIngestionStore, TransitStopEdge } from '../../ingestion/gtfsFetcher.js';
import { loadAndRegisterRrtsYaml } from '../../ingestion/rrtsLoader.js';
import { StaticTrainScheduleStore } from '../../ingestion/trainScheduleLoader.js';
import { haversineMeters } from '../../stations/h3Index.js';
import { CanonicalStation, StationEntityResolver } from '../../stations/resolver.js';
import { createEnvelope } from '../../types/envelope.js';
import {
  RouteCoordinateInput,
  RouteStepSeconds,
  SingleModeRouteOption,
  SingleModeRoutingResponse,
  TransitProviderAdapter
} from './types.js';

const INTERCHANGE_BUFFER_SECONDS = 300; // 5 min line transfer buffer

interface GraphPathStep {
  edge: TransitStopEdge;
  fromStation: CanonicalStation;
  toStation: CanonicalStation;
}

// Primary GTFS + RRTS YAML Graph Planner (TRANSIT_PROVIDER = gtfs_local).
// Never invents transit routes: if no verified stations or graph path exist, returns "no transit data for this area".
export class GtfsLocalTransitAdapter implements TransitProviderAdapter {
  public readonly providerName = 'gtfs_local';

  constructor(
    private readonly gtfs: GtfsIngestionStore,
    private readonly fares: OperatorFareStore,
    private readonly resolver: StationEntityResolver,
    private readonly railStore: StaticTrainScheduleStore
  ) {
    loadAndRegisterRrtsYaml(this.gtfs, this.fares);
  }

  private resolveEndpointStation(
    input: RouteCoordinateInput,
    stationType: 'metro' | 'rrts' | 'rail'
  ): { status: 'ok'; station: CanonicalStation } | { status: 'ambiguous' | 'not_found'; message: string } {
    const res = this.resolver.resolveStation({
      id: input.stationId,
      name: input.name,
      lat: input.lat,
      lng: input.lng,
      stationType
    });

    if (res.value?.resolution === 'ambiguous') {
      return {
        status: 'ambiguous',
        message: `Ambiguous ${stationType} station for "${input.name ?? `${input.lat},${input.lng}`}"`
      };
    }

    const stn = res.value?.station;
    if (!stn) {
      return {
        status: 'not_found',
        message: 'no transit data for this area'
      };
    }

    // Ensure coordinate is within a reasonable catchment (<= 5 km) if resolved purely by coordinates
    const distM = haversineMeters(input.lat, input.lng, stn.lat, stn.lng);
    if (!input.stationId && !input.name && distM > 5000) {
      return {
        status: 'not_found',
        message: 'no transit data for this area'
      };
    }

    return { status: 'ok', station: stn };
  }

  async planTransit(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
    mode: 'metro' | 'rrts' | 'bus';
    departureTime?: string;
    reachByTime?: string;
  }): Promise<SingleModeRoutingResponse> {
    if (params.mode === 'bus') {
      return {
        status: 'no_data',
        message: 'no verified bus data for this area',
        mode: 'bus',
        routes: []
      };
    }

    const startRes = this.resolveEndpointStation(params.start, params.mode);
    if (startRes.status !== 'ok') {
      return {
        status: startRes.status === 'ambiguous' ? 'ambiguous_station' : 'no_data',
        message: startRes.message,
        mode: params.mode,
        routes: []
      };
    }

    const endRes = this.resolveEndpointStation(params.end, params.mode);
    if (endRes.status !== 'ok') {
      return {
        status: endRes.status === 'ambiguous' ? 'ambiguous_station' : 'no_data',
        message: endRes.message,
        mode: params.mode,
        routes: []
      };
    }

    const startStn = startRes.station;
    const endStn = endRes.station;

    if (startStn.id === endStn.id) {
      return {
        status: 'no_data',
        message: 'Origin and destination resolve to the same station',
        mode: params.mode,
        routes: []
      };
    }

    const graphSteps = this.findShortestGraphPath(startStn.id, endStn.id, params.mode);
    if (!graphSteps || graphSteps.length === 0) {
      return {
        status: 'no_data',
        message: 'no transit data for this area',
        mode: params.mode,
        routes: []
      };
    }

    const firstRoute = this.gtfs.getRouteById(graphSteps[0].edge.routeId);
    const operatorCode = firstRoute?.operatorCode ?? (params.mode === 'rrts' ? 'NCRTC' : 'DMRC');

    let totalDurationSeconds = 0;
    let totalDistanceKm = 0;
    let transfers = 0;
    let prevRouteId: string | null = null;
    const steps: RouteStepSeconds[] = [];
    const pathCoords: Array<[number, number]> = [[startStn.lat, startStn.lng]];

    for (const seg of graphSteps) {
      const routeDef = this.gtfs.getRouteById(seg.edge.routeId);
      const isTransfer = prevRouteId !== null && prevRouteId !== seg.edge.routeId;
      if (isTransfer) {
        transfers += 1;
        totalDurationSeconds += INTERCHANGE_BUFFER_SECONDS;
        steps.push({
          mode: 'walk',
          instruction: `Interchange at ${seg.fromStation.canonicalName} to ${routeDef?.lineName ?? seg.edge.routeId}`,
          durationSeconds: INTERCHANGE_BUFFER_SECONDS,
          distanceMeters: 150,
          fromStationId: seg.fromStation.id,
          toStationId: seg.fromStation.id
        });
      } else if (prevRouteId === null) {
        // Initial average headway wait (half of peak headway)
        const waitS = Math.round(seg.edge.headwayPeakSeconds / 2);
        totalDurationSeconds += waitS;
      }

      prevRouteId = seg.edge.routeId;
      totalDurationSeconds += seg.edge.scheduledTravelSeconds;
      totalDistanceKm += seg.edge.distanceKm;
      pathCoords.push([seg.toStation.lat, seg.toStation.lng]);

      steps.push({
        mode: params.mode,
        instruction: `${routeDef?.lineName ?? seg.edge.routeId}: ${seg.fromStation.canonicalName} to ${seg.toStation.canonicalName}`,
        durationSeconds: seg.edge.scheduledTravelSeconds,
        distanceMeters: Math.round(seg.edge.distanceKm * 1000),
        fromStationId: seg.fromStation.id,
        toStationId: seg.toStation.id,
        routeId: seg.edge.routeId,
        lineName: routeDef?.lineName
      });
    }

    const fareEnv = this.fares.lookupExactTransitFare({
      operatorCode,
      fromStationId: startStn.id,
      toStationId: endStn.id,
      trackDistanceKm: Math.round(totalDistanceKm * 100) / 100,
      dayType: 'weekday'
    });

    const sourceName = params.mode === 'rrts' ? 'operator_official' : 'operator_gtfs';
    const durationEnv = createEnvelope<number>({
      value: totalDurationSeconds,
      status: 'scheduled',
      sources: [sourceName],
      primarySourceWeight: params.mode === 'rrts' ? 1.0 : 0.95
    });

    const option: SingleModeRouteOption = {
      id: `${params.mode}_${startStn.id}_${endStn.id}`,
      mode: params.mode,
      label: 'Fastest',
      durationSeconds: durationEnv,
      distanceMeters: Math.round(totalDistanceKm * 1000),
      transfers,
      walkMeters: 120 + transfers * 150,
      fare: fareEnv,
      steps,
      path: pathCoords,
      providerLabel: params.mode === 'rrts' ? 'RRTS_YAML' : 'GTFS_GRAPH'
    };

    return {
      status: 'ok',
      message: 'ok',
      mode: params.mode,
      routes: [option]
    };
  }

  planStaticTrain(params: {
    start: RouteCoordinateInput;
    end: RouteCoordinateInput;
  }): SingleModeRoutingResponse {
    const startRes = this.resolveEndpointStation(params.start, 'rail');
    const endRes = this.resolveEndpointStation(params.end, 'rail');

    if (startRes.status !== 'ok' || endRes.status !== 'ok') {
      return {
        status:
          startRes.status === 'ambiguous' || endRes.status === 'ambiguous'
            ? 'ambiguous_station'
            : 'no_data',
        message: 'no transit data for this area',
        mode: 'train',
        routes: []
      };
    }

    const startStn = startRes.station;
    const endStn = endRes.station;
    const schedules = this.railStore.findSchedulesBetween(startStn.id, endStn.id);

    if (schedules.length === 0) {
      return {
        status: 'no_data',
        message: 'no transit data for this area',
        mode: 'train',
        routes: []
      };
    }

    const distMeters = haversineMeters(startStn.lat, startStn.lng, endStn.lat, endStn.lng);

    const routes: SingleModeRouteOption[] = schedules.map((sched, idx) => {
      const durationEnv = createEnvelope<number>({
        value: sched.travelDurationSeconds,
        status: 'scheduled',
        sources: ['operator_official'],
        primarySourceWeight: 0.95
      });

      const fareEnv = this.fares.lookupExactTransitFare({
        operatorCode: 'IR',
        fromStationId: startStn.id,
        toStationId: endStn.id
      });

      return {
        id: `ir_train_${sched.trainNumber}`,
        mode: 'train',
        label: idx === 0 ? 'Fastest' : 'Cheapest',
        durationSeconds: durationEnv,
        distanceMeters: distMeters,
        transfers: 0,
        walkMeters: 200,
        fare: fareEnv,
        steps: [
          {
            mode: 'train',
            instruction: `${sched.trainNumber} ${sched.trainName} (${startStn.canonicalName} to ${endStn.canonicalName})`,
            durationSeconds: sched.travelDurationSeconds,
            distanceMeters: distMeters,
            fromStationId: startStn.id,
            toStationId: endStn.id,
            trainNumber: sched.trainNumber
          }
        ],
        path: [
          [startStn.lat, startStn.lng],
          [endStn.lat, endStn.lng]
        ],
        providerLabel: 'IR_STATIC'
      };
    });

    return {
      status: 'ok',
      message: 'ok',
      mode: 'train',
      routes
    };
  }

  private findShortestGraphPath(
    startId: string,
    endId: string,
    mode: 'metro' | 'rrts'
  ): GraphPathStep[] | null {
    const dist = new Map<string, number>();
    const prevEdge = new Map<string, { prevId: string; edge: TransitStopEdge }>();
    const visited = new Set<string>();
    const queue: string[] = [startId];
    dist.set(startId, 0);

    while (queue.length > 0) {
      queue.sort((a, b) => (dist.get(a) ?? Infinity) - (dist.get(b) ?? Infinity));
      const currId = queue.shift()!;
      if (currId === endId) break;
      if (visited.has(currId)) continue;
      visited.add(currId);

      const currCost = dist.get(currId) ?? Infinity;
      const incoming = prevEdge.get(currId);

      for (const edge of this.gtfs.getEdgesFromStation(currId)) {
        const routeDef = this.gtfs.getRouteById(edge.routeId);
        if (!routeDef || routeDef.mode !== mode) continue;

        const transferPenalty =
          incoming && incoming.edge.routeId !== edge.routeId ? INTERCHANGE_BUFFER_SECONDS : 0;
        const nextCost = currCost + edge.scheduledTravelSeconds + transferPenalty;

        if (nextCost < (dist.get(edge.toStationId) ?? Infinity)) {
          dist.set(edge.toStationId, nextCost);
          prevEdge.set(edge.toStationId, { prevId: currId, edge });
          queue.push(edge.toStationId);
        }
      }
    }

    if (!prevEdge.has(endId)) return null;

    const steps: GraphPathStep[] = [];
    let cursor = endId;
    while (cursor !== startId) {
      const item = prevEdge.get(cursor);
      if (!item) return null;
      const fromStn = this.resolver.getStationById(item.prevId);
      const toStn = this.resolver.getStationById(cursor);
      if (!fromStn || !toStn) return null;
      steps.unshift({
        edge: item.edge,
        fromStation: fromStn,
        toStation: toStn
      });
      cursor = item.prevId;
    }

    return steps;
  }
}
