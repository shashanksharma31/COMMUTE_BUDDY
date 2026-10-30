import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import { OperatorFareStore } from './fareStore.js';
import { GtfsIngestionStore } from './gtfsFetcher.js';

const rrtsConfigSchema = z.object({
  meta: z.object({
    operator_code: z.string().min(1),
    line_name: z.string().min(1),
    source_url: z.string().url(),
    operator_deeplink: z.string().url(),
    effective_from: z.string().min(10)
  }),
  route: z.object({
    route_id: z.string().min(1),
    mode: z.literal('rrts'),
    headway_peak_s: z.number().int().positive(),
    headway_offpeak_s: z.number().int().positive()
  }),
  edges: z
    .array(
      z.object({
        from_station_id: z.string().min(1),
        to_station_id: z.string().min(1),
        sequence_no: z.number().int().positive(),
        distance_km: z.number().positive(),
        scheduled_travel_s: z.number().int().positive()
      })
    )
    .min(1),
  first_last_trains: z
    .array(
      z.object({
        station_id: z.string().min(1),
        direction_station_id: z.string().min(1),
        day_type: z.enum(['weekday', 'saturday', 'sunday', 'holiday']),
        first_train_time: z.string().regex(/^([01]\d|2[0-3]):([0-5]\d)$/),
        last_train_time: z.string().regex(/^([01]\d|2[0-3]):([0-5]\d)$/)
      })
    )
    .min(1),
  fares: z
    .array(
      z.object({
        from_station_id: z.string().min(1),
        to_station_id: z.string().min(1),
        standard_fare_inr: z.number().positive(),
        premium_fare_inr: z.number().positive()
      })
    )
    .min(1)
});

export type RrtsConfig = z.infer<typeof rrtsConfigSchema>;

// Lightweight strict parser for the versioned NCRTC Namo Bharat RRTS YAML file.
export function parseRrtsYaml(yamlContent: string): RrtsConfig {
  const lines = yamlContent.split(/\r?\n/);
  const result: Record<string, unknown> = {
    meta: {},
    route: {},
    edges: [],
    first_last_trains: [],
    fares: []
  };

  let currentSection = '';
  let currentListItem: Record<string, unknown> | null = null;

  const parseScalar = (rawVal: string): string | number => {
    const trimmed = rawVal.trim().replace(/^"(.*)"$/, '$1');
    const asNum = Number(trimmed);
    if (!Number.isNaN(asNum) && trimmed !== '' && !trimmed.includes(':') && !trimmed.includes('-')) {
      return asNum;
    }
    return trimmed;
  };

  for (const rawLine of lines) {
    const line = rawLine.replace(/#.*$/, '');
    if (!line.trim()) continue;

    const topMatch = line.match(/^([a-z_]+):\s*$/);
    if (topMatch) {
      currentSection = topMatch[1];
      currentListItem = null;
      continue;
    }

    if (currentSection === 'meta' || currentSection === 'route') {
      const kv = line.match(/^\s+([a-z0-9_]+):\s*(.+)$/);
      if (kv) {
        (result[currentSection] as Record<string, unknown>)[kv[1]] = parseScalar(kv[2]);
      }
      continue;
    }

    if (
      currentSection === 'edges' ||
      currentSection === 'first_last_trains' ||
      currentSection === 'fares'
    ) {
      const itemStart = line.match(/^\s*-\s+([a-z0-9_]+):\s*(.+)$/);
      if (itemStart) {
        currentListItem = { [itemStart[1]]: parseScalar(itemStart[2]) };
        (result[currentSection] as Array<Record<string, unknown>>).push(currentListItem);
        continue;
      }
      const itemProp = line.match(/^\s+([a-z0-9_]+):\s*(.+)$/);
      if (itemProp && currentListItem) {
        currentListItem[itemProp[1]] = parseScalar(itemProp[2]);
      }
    }
  }

  return rrtsConfigSchema.parse(result);
}

export function loadAndRegisterRrtsYaml(
  gtfs: GtfsIngestionStore,
  fares: OperatorFareStore,
  yamlFilePath?: string
): RrtsConfig {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const candidates = yamlFilePath
    ? [yamlFilePath]
    : [
        path.resolve(currentDir, '../../data/rrts/namo_bharat.yaml'),
        path.resolve(process.cwd(), 'data/rrts/namo_bharat.yaml'),
        path.resolve(process.cwd(), 'server/data/rrts/namo_bharat.yaml')
      ];

  for (const p of candidates) {
    if (fs.existsSync(p)) {
      const parsed = parseRrtsYaml(fs.readFileSync(p, 'utf8'));

      gtfs.registerRoute({
        routeId: parsed.route.route_id,
        operatorCode: parsed.meta.operator_code,
        mode: 'rrts',
        lineName: parsed.meta.line_name,
        source_url: parsed.meta.source_url,
        effective_from: parsed.meta.effective_from
      });

      for (const edge of parsed.edges) {
        gtfs.registerBidirectionalEdge({
          routeId: parsed.route.route_id,
          fromStationId: edge.from_station_id,
          toStationId: edge.to_station_id,
          sequenceNo: edge.sequence_no,
          distanceKm: edge.distance_km,
          scheduledTravelSeconds: edge.scheduled_travel_s,
          headwayPeakSeconds: parsed.route.headway_peak_s,
          headwayOffpeakSeconds: parsed.route.headway_offpeak_s
        });
      }

      for (const fl of parsed.first_last_trains) {
        gtfs.registerFirstLastTrain({
          routeId: parsed.route.route_id,
          stationId: fl.station_id,
          directionStationId: fl.direction_station_id,
          dayType: fl.day_type,
          firstTrainTime: fl.first_train_time,
          lastTrainTime: fl.last_train_time,
          source_url: parsed.meta.source_url,
          effective_from: parsed.meta.effective_from
        });
      }

      for (const f of parsed.fares) {
        fares.registerOdFare({
          operatorCode: parsed.meta.operator_code,
          fromStationId: f.from_station_id,
          toStationId: f.to_station_id,
          dayType: 'weekday',
          fareInr: f.standard_fare_inr,
          operator_deeplink: parsed.meta.operator_deeplink,
          source_url: parsed.meta.source_url,
          effective_from: parsed.meta.effective_from
        });
      }

      return parsed;
    }
  }

  throw new Error('Could not locate server/data/rrts/namo_bharat.yaml');
}
