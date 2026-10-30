import { DelhiPlace, getBackendBaseUrl } from '../data/places.js';
import { Route, VehicleMode } from './types.js';

export interface ChatContext {
  start: DelhiPlace | null;
  end: DelhiPlace | null;
  vehicle: VehicleMode;
  selectedRoute: Route | null;
  reachBy?: string;
}

export async function sendMessage(
  message: string,
  context: ChatContext
): Promise<string> {
  const baseUrl = getBackendBaseUrl();
  const res = await fetch(`${baseUrl}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({
      message,
      journey: context.selectedRoute?.rawJourneyPayload ?? undefined
    })
  });

  if (!res.ok) {
    throw new Error(`Chat API error: HTTP ${res.status}`);
  }

  const payload = (await res.json()) as {
    reply?: string | { value?: string | null };
  };
  if (typeof payload.reply === 'string') {
    return payload.reply;
  }
  return payload.reply?.value ?? 'Unable to verify transit response right now.';
}
