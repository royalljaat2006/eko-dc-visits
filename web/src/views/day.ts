/**
 * AM DAY VIEW: date-scoped visits table + Leaflet map, polling GET /dashboard/visits
 * every 10 s (plan choice: polling, not websockets). Pauses while the tab is hidden.
 *
 * SECURITY BOUNDARY NOTE — no client-side filtering of visits, ever.
 * Role scoping is SERVER-SIDE (contracts/c6-permissions/matrix.yaml: AM scope =
 * assigned-dcs via GeoAssignment; C2 /dashboard/visits: "scoping is server-side —
 * clients render what they receive"). This view renders exactly what the API returns.
 *
 * PERF: Leaflet + its marker images are ~150 kB minified — the single biggest
 * chunk in the app, and only this one view needs them. They are code-split behind
 * a dynamic import() so the login → overview path never downloads them (see
 * vite.config.ts manualChunks). The view shell + table render immediately; the
 * map hydrates a tick later when the chunk resolves.
 */
import type * as LT from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { ApiError, listVisits, type Visit } from '../api/client.ts';
import { formatIstDateTime, todayIstDate } from '../lib/format.ts';
import { geofenceChip, syncChip, plannedLabel } from '../lib/chips.ts';

const POLL_MS = 10_000;

export function renderDayView(root: HTMLElement): () => void {
  // The app shell (main.ts) owns who/logout; this view owns the day controls.
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Visits</h1>
        <label>Date (IST) <input id="date" type="date" /></label>
      </header>
      <p id="day-status" class="status" role="status"></p>
      <div id="day-error" class="error-box" role="alert" hidden>
        <span id="day-error-msg"></span>
        <button id="retry" type="button">Retry</button>
      </div>
      <div id="map" class="map"></div>
      <table class="visits">
        <thead>
          <tr>
            <th>DC</th><th>Location</th><th>Check-in (IST)</th>
            <th>Geofence</th><th>Planned</th><th>Sync</th>
          </tr>
        </thead>
        <tbody id="visits-body"></tbody>
      </table>
      <p id="empty" class="empty" hidden>No visits synced yet for this date</p>
    </main>
  `;

  const dateEl = root.querySelector<HTMLInputElement>('#date')!;
  const statusEl = root.querySelector<HTMLElement>('#day-status')!;
  const errorBox = root.querySelector<HTMLElement>('#day-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#day-error-msg')!;
  const retryBtn = root.querySelector<HTMLButtonElement>('#retry')!;
  const tbody = root.querySelector<HTMLTableSectionElement>('#visits-body')!;
  const emptyEl = root.querySelector<HTMLElement>('#empty')!;

  dateEl.value = todayIstDate();

  let disposed = false;
  let teardown: (() => void) | null = null;

  // Load Leaflet + map wiring off the critical path. Everything that needs `L`
  // lives inside this async setup; the sync return below only flips `disposed`
  // and calls whatever teardown the setup managed to register.
  void (async () => {
    const [mod, iconPng, icon2xPng, shadowPng] = await Promise.all([
      import('leaflet'),
      import('leaflet/dist/images/marker-icon.png'),
      import('leaflet/dist/images/marker-icon-2x.png'),
      import('leaflet/dist/images/marker-shadow.png'),
    ]);
    if (disposed) return;
    const L = (mod as { default?: typeof LT }).default ?? (mod as unknown as typeof LT);

    // Leaflet's default icon URLs break under bundlers; point them at Vite-resolved assets.
    const markerIcon = L.icon({
      iconUrl: iconPng.default,
      iconRetinaUrl: icon2xPng.default,
      shadowUrl: shadowPng.default,
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41],
    });

    // Map. Default view: India; fitBounds once per date when data arrives.
    const map = L.map(root.querySelector<HTMLElement>('#map')!).setView([22.97, 78.65], 5);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(map);
    const markers = L.layerGroup().addTo(map);

    let timer: ReturnType<typeof setInterval> | null = null;
    let inflight: AbortController | null = null;
    let fittedForDate: string | null = null;

    function renderVisits(visits: readonly Visit[]): void {
      errorBox.hidden = true;
      emptyEl.hidden = visits.length > 0;

      // Table — render exactly what the API returned (see C6 note above).
      tbody.replaceChildren(
        ...visits.map((v) => {
          const tr = document.createElement('tr');
          const geo = geofenceChip(v.geofence_result, v.out_of_radius_reason);
          const sync = syncChip(v.sync_state);
          tr.append(
            td(v.dc_name ?? '—'),
            td(v.location_name ? `${v.location_name} (${v.location_code ?? '—'})` : (v.location_code ?? '—')),
            td(formatIstDateTime(v.checkin.occurred_at)),
            tdChip(geo.label, geo.className, geo.title),
            td(plannedLabel(v.planned)),
            tdChip(sync.label, sync.className, sync.title),
          );
          return tr;
        }),
      );

      // Map markers — one per visit at the check-in fix.
      markers.clearLayers();
      const points: LT.LatLngExpression[] = [];
      for (const v of visits) {
        const { lat, lng } = v.checkin.fix;
        points.push([lat, lng]);
        L.marker([lat, lng], { icon: markerIcon })
          .bindPopup(
            `<strong>${escapeHtml(v.location_name ?? v.location_code ?? 'Unknown location')}</strong><br>` +
              escapeHtml(formatIstDateTime(v.checkin.occurred_at)),
          )
          .addTo(markers);
      }
      if (points.length > 0 && fittedForDate !== dateEl.value) {
        map.fitBounds(L.latLngBounds(points).pad(0.2), { maxZoom: 15 });
        fittedForDate = dateEl.value;
      }
    }

    async function refresh(showLoading: boolean): Promise<void> {
      if (disposed || !dateEl.value) return;
      inflight?.abort();
      const ac = new AbortController();
      inflight = ac;
      if (showLoading) statusEl.textContent = 'Loading…';
      try {
        const res = await listVisits(dateEl.value, ac.signal);
        if (disposed || ac.signal.aborted) return;
        statusEl.textContent = `Last updated ${formatIstDateTime(new Date().toISOString())} IST`;
        renderVisits(res.items);
      } catch (err) {
        if (disposed || ac.signal.aborted) return;
        if (err instanceof DOMException && err.name === 'AbortError') return;
        statusEl.textContent = '';
        // NEVER fabricate or cache-fake data: clear stale rows/markers, show the error.
        tbody.replaceChildren();
        markers.clearLayers();
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
    // Pause polling while the tab is hidden; catch up immediately on return.
    function onVisibility(): void {
      if (document.hidden) {
        stopPolling();
      } else {
        void refresh(false);
        startPolling();
      }
    }
    document.addEventListener('visibilitychange', onVisibility);

    const onDateChange = (): void => {
      fittedForDate = null;
      void refresh(true);
    };
    const onRetry = (): void => void refresh(true);
    dateEl.addEventListener('change', onDateChange);
    retryBtn.addEventListener('click', onRetry);

    teardown = () => {
      stopPolling();
      inflight?.abort();
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
