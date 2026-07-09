import type { GeoPoint } from "./domain/types.js";

const EARTH_RADIUS_M = 6371008.8;

/** Great-circle distance in metres between two points (ADR-0004 distance basis). */
export function haversineMeters(a: Pick<GeoPoint, "lat" | "lng">, b: Pick<GeoPoint, "lat" | "lng">): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const sinLat = Math.sin(dLat / 2);
  const sinLng = Math.sin(dLng / 2);
  const h = sinLat * sinLat + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * sinLng * sinLng;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

const IST_OFFSET_MS = 5.5 * 3600 * 1000; // Asia/Kolkata, no DST (ADR-0009: store UTC, render IST)

/** IST calendar date (YYYY-MM-DD) of an ISO instant. */
export function istDateOf(iso: string | Date): string {
  const t = typeof iso === "string" ? new Date(iso).getTime() : iso.getTime();
  return new Date(t + IST_OFFSET_MS).toISOString().slice(0, 10);
}
