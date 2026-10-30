// Shared duration formatter (all backend durations are in seconds):
// - Under 60 min (< 3600s): "42 min"
// - 60 min to < 24 hr: "1 hr 11 min" (or "2 hr")
// - Multi-day (>= 86400s): "1 d 4 hr" (or "2 d")
export function formatDuration(durationSeconds: number): string {
  const safeSeconds = Math.max(0, Math.round(durationSeconds));
  const totalMinutes = Math.max(1, Math.round(safeSeconds / 60));

  if (totalMinutes < 60) {
    return `${totalMinutes} min`;
  }

  const totalHours = Math.floor(totalMinutes / 60);
  const remMinutes = totalMinutes % 60;

  if (totalHours < 24) {
    return remMinutes > 0 ? `${totalHours} hr ${remMinutes} min` : `${totalHours} hr`;
  }

  const days = Math.floor(totalHours / 24);
  const remHours = totalHours % 24;
  return remHours > 0 ? `${days} d ${remHours} hr` : `${days} d`;
}
