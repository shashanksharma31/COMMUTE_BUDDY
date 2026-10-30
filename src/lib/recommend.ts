import { Route } from './types';

export function scoreRoute(route: Route, allRoutes: Route[]): number {
  const maxMinutes = Math.max(1, ...allRoutes.map((r) => r.minutes));
  const maxCost = Math.max(1, ...allRoutes.map((r) => r.costInr));

  const normalizedTime = route.minutes / maxMinutes;
  const normalizedCost = maxCost === 0 ? 0 : route.costInr / maxCost;
  const reliabilityBonus = (Math.min(10, Math.max(0, route.reliability)) / 10) * 0.08;

  return normalizedTime * 0.78 + normalizedCost * 0.14 - reliabilityBonus;
}

// Picks the Recommended route by a combined time-and-cost score
export function recommendRoute(routes: Route[]): Route | null {
  if (routes.length === 0) return null;
  return routes.reduce((best, current) =>
    scoreRoute(current, routes) < scoreRoute(best, routes) ? current : best
  );
}
