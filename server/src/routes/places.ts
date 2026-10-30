import { Router } from 'express';
import { z } from 'zod';
import { AppEnv, env as defaultEnv, MissingEnvKeyError } from '../env.js';
import { sharedCache, TtlCache } from '../lib/cache.js';
import { resilienceManager, SourceResilienceManager } from '../lib/resilience.js';
import { NominatimProvider } from '../providers/places/nominatimProvider.js';
import { PhotonPlacesProvider } from '../providers/places/photonProvider.js';

const searchQuerySchema = z.object({
  q: z.string().trim().min(1, 'Query parameter "q" is required').max(120),
  lat: z.coerce.number().min(6.0).max(38.0).optional(),
  lng: z.coerce.number().min(68.0).max(98.0).optional()
});

const reverseQuerySchema = z.object({
  lat: z.coerce.number().min(6.0, 'Latitude must be within India').max(38.0, 'Latitude must be within India'),
  lng: z.coerce.number().min(68.0, 'Longitude must be within India').max(98.0, 'Longitude must be within India')
});

export function createPlacesRouter(deps?: {
  appEnv?: AppEnv;
  resilience?: SourceResilienceManager;
  cache?: TtlCache;
  fetchFn?: typeof fetch;
  nominatimMinIntervalMs?: number;
}): Router {
  const router = Router();
  const activeEnv = deps?.appEnv ?? defaultEnv;
  const activeResilience = deps?.resilience ?? resilienceManager;
  const activeCache = deps?.cache ?? sharedCache;

  const photon = new PhotonPlacesProvider(
    activeEnv,
    activeResilience,
    activeCache,
    deps?.fetchFn
  );
  const nominatim = new NominatimProvider(
    activeEnv,
    activeResilience,
    activeCache,
    deps?.fetchFn,
    deps?.nominatimMinIntervalMs
  );

  // GET /api/places?q= — India-restricted search-as-you-type (max 5 suggestions + stationHint)
  router.get('/places', async (req, res) => {
    const parsed = searchQuerySchema.safeParse(req.query);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid search query parameters',
        issues: parsed.error.issues.map((i) => i.message)
      });
      return;
    }

    try {
      const envelope = await photon.searchIndiaPlaces(parsed.data.q, {
        lat: parsed.data.lat,
        lng: parsed.data.lng,
        maxResults: 5
      });
      res.json(envelope);
    } catch (err) {
      if (err instanceof MissingEnvKeyError) {
        res.status(503).json({
          error: err.message,
          missingKey: err.keyName,
          status: 'unavailable'
        });
        return;
      }
      res.status(502).json({
        error: err instanceof Error ? err.message : 'Place search failed',
        status: 'unavailable'
      });
    }
  });

  // GET /api/places/reverse?lat=&lng= — Reverse geocoding for "Your location"
  router.get('/places/reverse', async (req, res) => {
    const parsed = reverseQuerySchema.safeParse(req.query);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid coordinates for India reverse geocoding',
        issues: parsed.error.issues.map((i) => i.message)
      });
      return;
    }

    try {
      const envelope = await nominatim.reverseGeocodeIndia(
        parsed.data.lat,
        parsed.data.lng
      );
      if (!envelope.value) {
        res.status(404).json({
          error: 'No verified location found in India for the given coordinates',
          envelope
        });
        return;
      }
      res.json(envelope);
    } catch (err) {
      if (err instanceof MissingEnvKeyError) {
        res.status(503).json({
          error: err.message,
          missingKey: err.keyName,
          status: 'unavailable'
        });
        return;
      }
      res.status(502).json({
        error: err instanceof Error ? err.message : 'Reverse geocoding failed',
        status: 'unavailable'
      });
    }
  });

  return router;
}

export const placesRouter = createPlacesRouter();
