import { Router } from 'express';
import { z } from 'zod';
import {
  StationEntityResolver,
  stationResolver as defaultResolver
} from '../stations/resolver.js';

const resolveQuerySchema = z.object({
  id: z.string().trim().optional(),
  gtfsStopId: z.string().trim().optional(),
  irStationCode: z.string().trim().optional(),
  osmNodeId: z.coerce.number().int().optional(),
  googlePlaceId: z.string().trim().optional(),
  wikidataId: z.string().trim().optional(),
  name: z.string().trim().optional(),
  lat: z.coerce.number().min(6.0).max(38.0).optional(),
  lng: z.coerce.number().min(68.0).max(98.0).optional(),
  cityCode: z.string().trim().optional(),
  stationType: z.enum(['metro', 'rail', 'rrts', 'bus']).optional()
});

export function createStationsRouter(resolver: StationEntityResolver = defaultResolver): Router {
  const router = Router();

  // GET /api/stations/resolve — Resolves a canonical station or returns an explicit ambiguity list
  router.get('/stations/resolve', (req, res) => {
    const parsed = resolveQuerySchema.safeParse(req.query);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid station resolution query parameters',
        issues: parsed.error.issues.map((i) => i.message)
      });
      return;
    }

    const envelope = resolver.resolveStation(parsed.data);
    if (envelope.value?.resolution === 'not_found') {
      res.status(404).json(envelope);
      return;
    }
    res.json(envelope);
  });

  // GET /api/stations/:id/hub — Returns interchange hub walking links (<= 400m) for a canonical station
  router.get('/stations/:id/hub', (req, res) => {
    const station = resolver.getStationById(req.params.id);
    if (!station) {
      res.status(404).json({
        error: `Canonical station "${req.params.id}" not found`,
        status: 'unavailable'
      });
      return;
    }
    const links = resolver.getHubWalkingLinks(station.id);
    res.json({
      station,
      hubLinks: links
    });
  });

  return router;
}

export const stationsRouter = createStationsRouter();
