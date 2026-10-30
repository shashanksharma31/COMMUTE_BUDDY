import path from 'path';
import dotenv from 'dotenv';
import { z } from 'zod';

dotenv.config({ path: path.resolve(process.cwd(), '../.env') });
dotenv.config();

// Schema for backend environment variables.
const envSchema = z.object({
  PORT: z.coerce.number().int().positive().default(3001),
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  FRONTEND_ORIGIN: z.string().min(1).default('http://localhost:5173'),
  NOMINATIM_USER_AGENT: z.string().default(''),
  PHOTON_BASE_URL: z.string().url().default('https://photon.komoot.io'),
  NOMINATIM_BASE_URL: z.string().url().default('https://nominatim.openstreetmap.org'),
  OVERPASS_BASE_URL: z.string().url().default('https://overpass-api.de/api/interpreter'),
  DATABASE_URL: z.string().default(''),
  REDIS_URL: z.string().default(''),
  ORS_API_KEY: z.string().default(''),
  ORS_BASE_URL: z.string().url().default('https://api.openrouteservice.org'),
  TRAFFIC_PROVIDER: z.enum(['tomtom', 'here']).default('tomtom'),
  TOMTOM_API_KEY: z.string().default(''),
  HERE_API_KEY: z.string().default(''),
  TRANSIT_PROVIDER: z.enum(['gtfs_local', 'google_routes', 'here_transit', 'ondc']).default('gtfs_local'),
  GOOGLE_ROUTES_API_KEY: z.string().default(''),
  GOOGLE_ROUTES_DAILY_CAP: z.coerce.number().int().nonnegative().default(500),
  OTD_DELHI_API_KEY: z.string().default(''),
  TRAIN_PRIMARY_PROVIDER: z.string().default(''),
  TRAIN_PRIMARY_API_KEY: z.string().default(''),
  TRAIN_SECONDARY_PROVIDER: z.string().default(''),
  TRAIN_SECONDARY_API_KEY: z.string().default(''),
  GEMINI_API_KEY: z.string().default(''),
  GEMINI_MODEL: z.string().default('gemini-2.5-flash')
});

export type AppEnv = z.infer<typeof envSchema>;

export class MissingEnvKeyError extends Error {
  public readonly keyName: string;
  public readonly featureName: string;

  constructor(keyName: string, featureName: string) {
    super(
      `Missing required environment variable "${keyName}" for ${featureName}. ` +
        `Please add ${keyName} to server/.env (see server/.env.example).`
    );
    this.name = 'MissingEnvKeyError';
    this.keyName = keyName;
    this.featureName = featureName;
  }
}

export function loadEnv(rawEnv: NodeJS.ProcessEnv = process.env): AppEnv {
  const parsed = envSchema.safeParse(rawEnv);
  if (!parsed.success) {
    const issues = parsed.error.issues
      .map((i) => `${i.path.join('.')}: ${i.message}`)
      .join('; ');
    throw new Error(`Invalid environment configuration: ${issues}`);
  }
  return parsed.data;
}

// Throws a clear error when a specific provider key is missing at call time.
export function requireEnvKey(
  env: AppEnv,
  keyName: keyof AppEnv,
  featureName: string
): string {
  const val = String(env[keyName] ?? '').trim();
  if (!val) {
    throw new MissingEnvKeyError(String(keyName), featureName);
  }
  return val;
}

export const env = loadEnv();
