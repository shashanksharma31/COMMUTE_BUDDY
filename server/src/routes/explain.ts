import { Router } from 'express';
import { z } from 'zod';
import { RegulatedJourneyExplainer } from '../ai/regulatedExplainer.js';
import { AppEnv, env as defaultEnv } from '../env.js';
import { MultiModalJourneyOption } from '../journeys/journeyPlanner.js';
import { resilienceManager, SourceResilienceManager } from '../lib/resilience.js';

const explainRequestSchema = z.object({
  journey: z.record(z.unknown()),
  question: z.string().trim().max(300).optional()
});

const chatRequestSchema = z.object({
  message: z.string().trim().min(1).max(300),
  journey: z.record(z.unknown()).optional()
});

export function createExplainRouter(deps?: {
  appEnv?: AppEnv;
  resilience?: SourceResilienceManager;
  fetchFn?: typeof fetch;
}): Router {
  const router = Router();
  const explainer = new RegulatedJourneyExplainer(
    deps?.appEnv ?? defaultEnv,
    deps?.resilience ?? resilienceManager,
    deps?.fetchFn
  );

  // POST /api/journeys/explain — Regulated explanation of a verified MultiModalJourneyOption
  router.post('/journeys/explain', async (req, res) => {
    const parsed = explainRequestSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid journey explanation request payload',
        issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`)
      });
      return;
    }

    const journey = parsed.data.journey as unknown as MultiModalJourneyOption;
    if (!journey || !Array.isArray(journey.legs) || journey.legs.length === 0) {
      res.status(400).json({
        error: 'Verified journey payload must contain at least one leg'
      });
      return;
    }

    const result = await explainer.explainJourney({
      journey,
      question: parsed.data.question
    });

    res.json(result);
  });

  // POST /api/chat — Regulated trip assistant answering strictly from verified journey envelopes
  router.post('/chat', async (req, res) => {
    const parsed = chatRequestSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({
        error: 'Invalid chat request payload',
        issues: parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`)
      });
      return;
    }

    const journey = parsed.data.journey as unknown as MultiModalJourneyOption | undefined;
    if (!journey || !Array.isArray(journey.legs) || journey.legs.length === 0) {
      res.json({
        reply:
          'Select a verified route first to ask questions about timings, fares, or transfers.',
        generatedBy: 'deterministic_template',
        hallucinationRejected: false,
        referencedLegIds: []
      });
      return;
    }

    const result = await explainer.explainJourney({
      journey,
      question: parsed.data.message
    });

    res.json({
      reply: `${result.summary} ${result.confidenceNote}`,
      ...result
    });
  });

  return router;
}

export const explainRouter = createExplainRouter();
