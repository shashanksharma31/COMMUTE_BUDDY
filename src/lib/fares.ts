import { DataLabel } from './types.js';

// Formats verified backend fare envelopes without inventing or linearly inferring fares.
export function formatVerifiedFareDisplay(params: {
  fareInr: number | null | undefined;
  status: string;
  isEstimate?: boolean;
}): { costInr: number; costDisplay: string; isEstimate: boolean } {
  if (
    params.status === 'unavailable' ||
    params.fareInr === null ||
    params.fareInr === undefined
  ) {
    return {
      costInr: 0,
      costDisplay: 'Fare unavailable',
      isEstimate: false
    };
  }

  const isEst = params.status === 'estimate' || Boolean(params.isEstimate);
  const rounded = Math.max(0, Math.round(params.fareInr));

  return {
    costInr: rounded,
    costDisplay: isEst ? `₹${rounded} (Estimated)` : `₹${rounded}`,
    isEstimate: isEst
  };
}

export function mapEnvelopeStatusToDataLabel(status?: string): DataLabel {
  switch (status) {
    case 'live':
      return 'LIVE';
    case 'scheduled':
      return 'TIMETABLE';
    case 'estimate':
      return 'ESTIMATED';
    case 'stale':
      return 'STALE';
    case 'unverified':
      return 'UNVERIFIED';
    default:
      return 'UNAVAILABLE';
  }
}
