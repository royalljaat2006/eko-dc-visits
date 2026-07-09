/**
 * AM DAY VIEW: date-scoped visits table + Leaflet map, polling GET /dashboard/visits
 * every 10 s (plan choice: polling, not websockets). Pauses while the tab is hidden.
 *
 * SECURITY BOUNDARY NOTE — no client-side filtering of visits, ever.
 * Role scoping is SERVER-SIDE (contracts/c6-permissions/matrix.yaml: AM scope =
 * assigned-dcs via GeoAssignment; C2 /dashboard/visits: "scoping is server-side —
 * clients render what they receive"). This view renders exactly what the API returns.
 */
import * as L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import markerIconUrl from 'leaflet/dist/images/marker-icon.png';
import markerIcon2xUrl from 'leaflet/dist/images/marker-icon-2x.png';
import markerShadowUrl from 'leaflet/dist/images/marker-shadow.png';
import { ApiError, listVisits, clearSession, getSession, type Visit } from '../api/client.ts';
import { formatIstDateTime, todayIstDate } from '../lib/format.ts';
import { geofenceChip, syncChip, plannedLabel } from '../lib/chips.ts';

// Leaflet's default icon URLs break under bundlers; point them at Vite-resolved assets.
const markerIcon = L.icon({
  iconUrl: markerIconUrl,
  iconRetinaUrl: markerIcon2xUrl,
  shadowUrl: markerShadowUrl,
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
});

const POLL_MS = 10_000;

export function renderDayView(root: HTMLElement, onLogout: () => void): () => void {
  const user = getSession()?.user;
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Circle Day View</h1>
        <span class="who">${escapeHtml(user ? `${user.name} (${user.role === 'CIRCLE_HEAD' ? 'Circle Head' : user.role})` : '')}</span>
        <label>Date (IST) <input id="date" type="date" /></label>
        <button id="logout" type="button">Log out</button>
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
  let disposed = false;

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
    const points: L.LatLngExpression[] = [];
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

  dateEl.addEventListener('change', () => {
    fittedForDate = null;
    void refresh(true);
  });
  retryBtn.addEventListener('click', () => void refresh(true));
  root.querySelector<HTMLButtonElement>('#logout')!.addEventListener('click', () => {
    clearSession();
    onLogout();
  });

  void refresh(true);
  if (!document.hidden) startPolling();

  return () => {
    disposed = true;
    stopPolling();
    inflight?.abort();
    document.removeEventListener('visibilitychange', onVisibility);
    map.remove();
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
