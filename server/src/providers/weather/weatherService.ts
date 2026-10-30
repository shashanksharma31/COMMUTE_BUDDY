import { TtlCache } from '../../lib/cache.js';
import { SourceResilienceManager } from '../../lib/resilience.js';
import { createEnvelope, DataEnvelope } from '../../types/envelope.js';

export interface LiveWeatherReport {
  lat: number;
  lng: number;
  temperatureCelsius: DataEnvelope<number>;
  precipitationMmPerHour: DataEnvelope<number>;
  windSpeedKmh: number;
  conditionText: string;
  isHeavyRain: boolean;
  isExtremeHeat: boolean;
  walkSpeedMultiplier: number; // <= 1.0 (slows down outdoor walk legs in rain/heat)
  bikeSpeedMultiplier: number; // <= 1.0
  advisoryMessage: string | null;
}

interface OpenMeteoCurrentResponse {
  current?: {
    time?: string;
    temperature_2m?: number;
    precipitation?: number;
    weather_code?: number;
    wind_speed_10m?: number;
  };
}

function describeWmoWeatherCode(code: number): string {
  if (code === 0) return 'Clear sky';
  if (code <= 3) return 'Partly cloudy';
  if (code === 45 || code === 48) return 'Fog';
  if (code >= 51 && code <= 67) return 'Rain';
  if (code >= 80 && code <= 82) return 'Rain showers';
  if (code >= 95) return 'Thunderstorm';
  return 'Moderate conditions';
}

// Live Weather Service powered by Open-Meteo (with 10-minute / 600s TTL cache).
// Adjusts outdoor walk/bike speeds and surfaces advisories during heavy rain or extreme heat.
export class LiveWeatherService {
  constructor(
    private readonly resilience: SourceResilienceManager,
    private readonly cache: TtlCache,
    private readonly fetchFn: typeof fetch = fetch
  ) {}

  async fetchWeather(params: { lat: number; lng: number }): Promise<LiveWeatherReport> {
    const cacheKey = `weather:open_meteo:${params.lat.toFixed(3)},${params.lng.toFixed(3)}`;
    const cached = this.cache.get<LiveWeatherReport>(cacheKey);
    if (cached) {
      return cached;
    }

    const url = `https://api.open-meteo.com/v1/forecast?latitude=${params.lat}&longitude=${params.lng}&current=temperature_2m,precipitation,weather_code,wind_speed_10m`;

    const data = await this.resilience.execute<OpenMeteoCurrentResponse>(
      'open_meteo',
      async (signal) => {
        const res = await this.fetchFn(url, { signal });
        if (!res.ok) {
          throw new Error(`Open-Meteo HTTP ${res.status}`);
        }
        return (await res.json()) as OpenMeteoCurrentResponse;
      }
    );

    const curr = data.current;
    if (!curr || curr.temperature_2m === undefined || curr.precipitation === undefined) {
      throw new Error('Open-Meteo returned incomplete current weather payload');
    }

    const tempC = Math.round(curr.temperature_2m * 10) / 10;
    const precipMm = Math.max(0, Math.round(curr.precipitation * 10) / 10);
    const weatherCode = curr.weather_code ?? 0;
    const windKmh = Math.max(0, Math.round((curr.wind_speed_10m ?? 0) * 10) / 10);

    const isHeavyRain = precipMm > 7.5 || weatherCode >= 95;
    const isModerateRain = !isHeavyRain && precipMm > 1.0;
    const isExtremeHeat = tempC >= 42.0;

    let walkSpeedMultiplier = 1.0;
    let bikeSpeedMultiplier = 1.0;
    let advisoryMessage: string | null = null;

    if (isHeavyRain) {
      walkSpeedMultiplier = 0.75;
      bikeSpeedMultiplier = 0.65;
      advisoryMessage =
        'Heavy rain reported; outdoor walk and bike durations increased';
    } else if (isModerateRain) {
      walkSpeedMultiplier = 0.88;
      bikeSpeedMultiplier = 0.8;
      advisoryMessage = 'Light to moderate rain; allow extra time for outdoor transfers';
    } else if (isExtremeHeat) {
      walkSpeedMultiplier = 0.85;
      bikeSpeedMultiplier = 0.85;
      advisoryMessage = 'Extreme heat (>= 42C); prefer shaded or metro transfers';
    }

    const weight = this.resilience.getEffectiveWeight('open_meteo');

    const report: LiveWeatherReport = {
      lat: params.lat,
      lng: params.lng,
      temperatureCelsius: createEnvelope<number>({
        value: tempC,
        status: 'live',
        sources: ['open_meteo'],
        primarySourceWeight: weight,
        halfLifeSeconds: 600
      }),
      precipitationMmPerHour: createEnvelope<number>({
        value: precipMm,
        status: 'live',
        sources: ['open_meteo'],
        primarySourceWeight: weight,
        halfLifeSeconds: 600
      }),
      windSpeedKmh: windKmh,
      conditionText: describeWmoWeatherCode(weatherCode),
      isHeavyRain,
      isExtremeHeat,
      walkSpeedMultiplier,
      bikeSpeedMultiplier,
      advisoryMessage
    };

    // 10-minute (600s) TTL cache
    this.cache.set(cacheKey, report, 600);
    return report;
  }
}
