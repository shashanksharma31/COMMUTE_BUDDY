import { Router } from 'express';
import { z } from 'zod';
import { sharedCache, TtlCache } from '../lib/cache.js';
import { resilienceManager, SourceResilienceManager } from '../lib/resilience.js';
import { LiveWeatherService } from '../providers/weather/weatherService.js';

const weatherQuerySchema = z.object({
  lat: z.coerce.number().min(6.0).max(38.0),
  lng: z.coerce.number().min(68.0).max(98.0)
});

export function createWeatherRouter(deps?: {
  resilience?: SourceResilienceManager;
  cache?: TtlCache;
  fetchFn?: typeof fetch;
}): Router {
  const router = Router();
  const service = new LiveWeatherService(
    deps?.resilience ?? resilienceManager,
    deps?.cache ?? sharedCache,
    deps?.fetchFn
  );

  // GET /api/weather?lat=...&lng=...
  router.get('/weather', async (req, res) => {
    const parsed = weatherQuerySchema.safeParse(req.query);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid coordinates for weather query (must be within India)',
        issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`)
      });
      return;
    }

    try {
      const report = await service.fetchWeather(parsed.data);
      res.json(report);
    } catch (err) {
      res.status(502).json({
        status: 'unavailable',
        error: err instanceof Error ? err.message : 'Weather service unavailable'
      });
    }
  });

  return router;
}

export const weatherRouter = createWeatherRouter();
