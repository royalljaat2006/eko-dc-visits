/**
 * LIVE TRACKING (C2 v0.12.0): Admin Portal map of every DC in the caller's
 * scope, derived from track.chunk evidence (never evidence itself — a pure,
 * overwritable projection, ADR-0003), plus bounded route-history playback for
 * a single DC + IST date. Polling, not push, matching the existing
 * /dashboard/visits convention (10 s).
 *
 * SECURITY BOUNDARY NOTE — no client-side filtering, ever. Scoping is
 * SERVER-SIDE (C6/ADR-0006); this view renders exactly what the API returns,
 * and route-history requests are allowed only for DCs the server already
 * decided are in scope (a 403 here is the server enforcing that, not a bug).
 */
import type * as LT from 'leaflet';
import 'leaflet/dist/leaflet.css';
import {
  ApiError,
  getRouteHistory,
  listLiveLocations,
  type LiveLocationRow,
  type RouteHistoryPoint,
} from '../api/client.ts';
import { formatIstDateTime, todayIstDate } from '../lib/format.ts';
import { liveLocationChip } from '../lib/chips.ts';

const POLL_MS = 10_000;

const STATE_COLOR: Record<LiveLocationRow['state'], string> = {
  live: '#1d7a3a',
  stale: '#d9860a',
  off_duty: '#5d564a',
  no_fix: '#5d564a',
};

export function renderLiveView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Live Tracking</h1>
        <label>Route date (IST) <input id="live-date" type="date" /></label>
      </header>
      <p id="live-status" class="status" role="status"></p>
      <div id="live-error" class="error-box" role="alert" hidden>
        <span id="live-error-msg"></span>
        <button id="live-retry" type="button">Retry</button>
      </div>
      <div class="live-layout">
        <div id="map" class="map live-map"></div>
        <div class="live-panel">
          <p class="live-legend">
            <span class="chip chip-green">LIVE</span>
            <span class="chip chip-amber">STALE</span>
            <span class="chip chip-neutral">OFF DUTY</span>
            <span class="chip chip-neutral">NO FIX</span>
          </p>
          <table class="visits">
            <thead>
              <tr><th>DC</th><th>Status</th><th>Last update (IST)</th><th>Accuracy</th></tr>
            </thead>
            <tbody id="live-body"></tbody>
          </table>
          <p id="live-empty" class="empty" hidden>No DCs in your scope</p>
          <p id="route-status" class="status" role="status"></p>
        </div>
      </div>
    </main>
  `;

  const dateEl = root.querySelector<HTMLInputElement>('#live-date')!;
  const statusEl = root.querySelector<HTMLElement>('#live-status')!;
  const errorBox = root.querySelector<HTMLElement>('#live-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#live-error-msg')!;
  const retryBtn = root.querySelector<HTMLButtonElement>('#live-retry')!;
  const tbody = root.querySelector<HTMLTableSectionElement>('#live-body')!;
  const emptyEl = root.querySelector<HTMLElement>('#live-empty')!;
  const routeStatusEl = root.querySelector<HTMLElement>('#route-status')!;

  dateEl.value = todayIstDate();

  let disposed = false;
  let teardown: (() => void) | null = null;
  let selectedDcId: string | null = null;

  void (async () => {
    const [mod, iconPng, icon2xPng, shadowPng] = await Promise.all([
      import('leaflet'),
      import('leaflet/dist/images/marker-icon.png'),
      import('leaflet/dist/images/marker-icon-2x.png'),
      import('leaflet/dist/images/marker-shadow.png'),
    ]);
    if (disposed) return;
    const L = (mod as { default?: typeof LT }).default ?? (mod as unknown as typeof LT);

    const markerIcon = L.icon({
      iconUrl: iconPng.default,
      iconRetinaUrl: icon2xPng.default,
      shadowUrl: shadowPng.default,
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41],
    });

    const map = L.map(root.querySelector<HTMLElement>('#map')!).setView([22.97, 78.65], 5);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(map);
    const dcMarkers = L.layerGroup().addTo(map);
    const routeLayer = L.layerGroup().addTo(map);

    let timer: ReturnType<typeof setInterval> | null = null;
    let inflight: AbortController | null = null;
    let routeInflight: AbortController | null = null;
    let fittedOnce = false;
    let latestRows: readonly LiveLocationRow[] = [];

    function renderRows(rows: readonly LiveLocationRow[]): void {
      errorBox.hidden = true;
      emptyEl.hidden = rows.length > 0;
      latestRows = rows;

      tbody.replaceChildren(
        ...rows.map((r) => {
          const tr = document.createElement('tr');
          if (r.dc_user_id === selectedDcId) tr.className = 'row-selected';
          const chip = liveLocationChip(r.state);
          tr.append(
            td(r.name),
            tdChip(chip.label, chip.className, chip.title),
            td(formatIstDateTime(r.captured_at)),
            td(r.accuracy_m === undefined || r.accuracy_m === null ? '—' : `±${Math.round(r.accuracy_m)} m`),
          );
          tr.addEventListener('click', () => selectDc(r.dc_user_id));
          tr.style.cursor = 'pointer';
          return tr;
        }),
      );

      dcMarkers.clearLayers();
      const points: LT.LatLngExpression[] = [];
      for (const r of rows) {
        if (r.lat === undefined || r.lng === undefined) continue;
        points.push([r.lat, r.lng]);
        L.circleMarker([r.lat, r.lng], {
          radius: r.dc_user_id === selectedDcId ? 10 : 7,
          color: STATE_COLOR[r.state],
          fillColor: STATE_COLOR[r.state],
          fillOpacity: r.state === 'live' ? 0.9 : 0.5,
          weight: 2,
        })
          .bindPopup(
            `<strong>${escapeHtml(r.name)}</strong><br>${escapeHtml(liveLocationChip(r.state).label)}` +
              (r.captured_at ? `<br>${escapeHtml(formatIstDateTime(r.captured_at))} IST` : ''),
          )
          .on('click', () => selectDc(r.dc_user_id))
          .addTo(dcMarkers);
      }
      if (points.length > 0 && !fittedOnce) {
        map.fitBounds(L.latLngBounds(points).pad(0.2), { maxZoom: 15 });
        fittedOnce = true;
      }
    }

    function renderRoute(points: readonly RouteHistoryPoint[]): void {
      routeLayer.clearLayers();
      if (points.length === 0) return;
      const sorted = [...points].sort((a, b) => a.t.localeCompare(b.t));
      const latlngs: LT.LatLngExpression[] = sorted.map((p) => [p.lat, p.lng]);
      L.polyline(latlngs, { color: '#2563eb', weight: 3, opacity: 0.8 }).addTo(routeLayer);
      const first = sorted[0]!;
      const last = sorted[sorted.length - 1]!;
      L.circleMarker([first.lat, first.lng], { radius: 6, color: '#2563eb', fillColor: '#fff', fillOpacity: 1, weight: 2 })
        .bindPopup(`Route start<br>${escapeHtml(formatIstDateTime(first.t))} IST`)
        .addTo(routeLayer);
      L.marker([last.lat, last.lng], { icon: markerIcon })
        .bindPopup(`Latest point<br>${escapeHtml(formatIstDateTime(last.t))} IST`)
        .addTo(routeLayer);
    }

    async function refreshRoute(): Promise<void> {
      routeInflight?.abort();
      if (!selectedDcId) {
        routeLayer.clearLayers();
        routeStatusEl.textContent = '';
        return;
      }
      const ac = new AbortController();
      routeInflight = ac;
      const dcId = selectedDcId;
      routeStatusEl.textContent = 'Loading route…';
      try {
        const res = await getRouteHistory(dateEl.value, dcId, ac.signal);
        if (disposed || ac.signal.aborted || selectedDcId !== dcId) return;
        renderRoute(res.points);
        const dcName = latestRows.find((r) => r.dc_user_id === dcId)?.name ?? 'this DC';
        routeStatusEl.textContent =
          res.points.length === 0
            ? `No track points for ${dcName} on ${dateEl.value}`
            : `${res.points.length} track point${res.points.length === 1 ? '' : 's'} for ${dcName} on ${dateEl.value}`;
      } catch (err) {
        if (disposed || ac.signal.aborted) return;
        if (err instanceof DOMException && err.name === 'AbortError') return;
        routeLayer.clearLayers();
        routeStatusEl.textContent =
          err instanceof ApiError ? `Route unavailable: ${err.message}` : 'Route unavailable: unexpected error';
      }
    }

    function selectDc(dcUserId: string): void {
      selectedDcId = selectedDcId === dcUserId ? null : dcUserId;
      renderRows(latestRows);
      void refreshRoute();
    }

    async function refresh(showLoading: boolean): Promise<void> {
      if (disposed) return;
      inflight?.abort();
      const ac = new AbortController();
      inflight = ac;
      if (showLoading) statusEl.textContent = 'Loading…';
      try {
        const res = await listLiveLocations(undefined, ac.signal);
        if (disposed || ac.signal.aborted) return;
        statusEl.textContent = `Last updated ${formatIstDateTime(new Date().toISOString())} IST`;
        renderRows(res.items);
        if (selectedDcId && !res.items.some((r) => r.dc_user_id === selectedDcId)) {
          selectedDcId = null; // the selected DC fell out of scope/vanished
        }
        if (selectedDcId && dateEl.value === todayIstDate()) {
          // Keep today's route growing in step with the live poll.
          void refreshRoute();
        }
      } catch (err) {
        if (disposed || ac.signal.aborted) return;
        if (err instanceof DOMException && err.name === 'AbortError') return;
        statusEl.textContent = '';
        tbody.replaceChildren();
        dcMarkers.clearLayers();
        emptyEl.hidden = true;
        errorBox.hidden = false;
        errorMsg.textContent =
          err instanceof ApiError
            ? err.networkFailure
              ? `API unreachable: ${err.problem.detail ?? 'network error'}`
              : `${err.problem.title} (HTTP ${err.problem.status})${err.problem.detail ? `: ${err.problem.detail}` : ''}`
            : err instanceof Error
              ? err.message
              : 'Unexpected error';
      }
    }

    function startPolling(): void {
      if (timer !== null) return;
      timer = setInterval(() => void refresh(false), POLL_MS);
    }
    function stopPolling(): void {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    }
    function onVisibility(): void {
      if (document.hidden) {
        stopPolling();
      } else {
        void refresh(false);
        startPolling();
      }
    }
    document.addEventListener('visibilitychange', onVisibility);

    const onDateChange = (): void => void refreshRoute();
    const onRetry = (): void => void refresh(true);
    dateEl.addEventListener('change', onDateChange);
    retryBtn.addEventListener('click', onRetry);

    teardown = () => {
      stopPolling();
      inflight?.abort();
      routeInflight?.abort();
      document.removeEventListener('visibilitychange', onVisibility);
      dateEl.removeEventListener('change', onDateChange);
      retryBtn.removeEventListener('click', onRetry);
      map.remove();
    };

    void refresh(true);
    if (!document.hidden) startPolling();
  })();

  return () => {
    disposed = true;
    teardown?.();
  };
}

function td(text: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.textContent = text;
  return cell;
}

function tdChip(label: string, className: string, title: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  const span = document.createElement('span');
  span.className = className;
  span.textContent = label;
  if (title) span.title = title;
  cell.appendChild(span);
  return cell;
}

function escapeHtml(s: string): string {
  return s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
