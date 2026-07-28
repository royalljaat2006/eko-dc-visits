/**
 * CSP DETAILS (spec §3) — the DC's own assigned CSPs: code, name, address,
 * lat/lng from the same master source as the CSP Workbench, plus last-visit
 * date and distance-from-here so the DC can prioritise the next visit.
 * "Get Directions" opens Google Maps via the universal link (works on
 * Android & iOS with no SDK). Edits are proposed one CSP at a time and go
 * live only after Circle Head/Admin approval.
 */
import { ApiError, createCspChangeRequest, getDcCspDetails, type CspDetail } from '../api/client.ts';

const EDIT_FIELDS: Array<{ key: string; label: string; numeric?: boolean }> = [
  { key: 'name', label: 'CSP Name' },
  { key: 'address', label: 'Full Address' },
  { key: 'lat', label: 'Latitude', numeric: true },
  { key: 'lng', label: 'Longitude', numeric: true },
  { key: 'mobile_number', label: 'Mobile Number' },
];

function haversineKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const h =
    Math.sin(rad(bLat - aLat) / 2) ** 2 +
    Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(rad(bLng - aLng) / 2) ** 2;
  return Math.round(2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h))) * 10) / 10;
}

export function renderCspDetailsView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>My CSPs</h1>
        <button id="cd-refresh" type="button">Refresh</button>
      </header>
      <p id="cd-status" class="status" role="status"></p>
      <div id="cd-error" class="error-box" role="alert" hidden>
        <span id="cd-error-msg"></span>
        <button id="cd-retry" type="button">Retry</button>
      </div>
      <div id="cd-cards" class="cards"></div>
      <p id="cd-empty" class="empty" hidden>No CSPs assigned to you yet</p>
    </main>
  `;

  const statusEl = root.querySelector<HTMLElement>('#cd-status')!;
  const errorBox = root.querySelector<HTMLElement>('#cd-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#cd-error-msg')!;
  const cardsEl = root.querySelector<HTMLElement>('#cd-cards')!;
  const emptyEl = root.querySelector<HTMLElement>('#cd-empty')!;

  let disposed = false;
  let here: { lat: number; lng: number } | null = null;

  function card(item: CspDetail): HTMLElement {
    const el = document.createElement('article');
    el.className = 'score-card';

    const head = document.createElement('div');
    head.className = 'score-head';
    const name = document.createElement('span');
    name.className = 'score-name';
    name.textContent = `${item.name}`;
    const code = document.createElement('span');
    code.className = 'score-points-label';
    code.textContent = item.code;
    head.append(name, code);

    const addr = document.createElement('p');
    addr.className = 'score-breakdown';
    addr.textContent = item.address || '— no address on record —';

    const meta = document.createElement('p');
    meta.className = 'score-breakdown';
    const distance = here ? `${haversineKm(here.lat, here.lng, item.lat, item.lng)} km away · ` : '';
    meta.textContent =
      distance +
      (item.last_visit_date ? `last visit ${item.last_visit_date}` : 'never visited') +
      ` · ${item.lat.toFixed(4)}, ${item.lng.toFixed(4)}`;

    const actions = document.createElement('div');
    actions.className = 'badges';
    const directions = document.createElement('a');
    directions.href = `https://www.google.com/maps/dir/?api=1&destination=${item.lat},${item.lng}`;
    directions.target = '_blank';
    directions.rel = 'noopener';
    directions.className = 'badge';
    directions.textContent = '🧭 Get Directions';
    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.textContent = 'Suggest edit';

    const form = document.createElement('form');
    form.className = 'cr-form';
    form.hidden = true;
    const inputs = new Map<string, HTMLInputElement>();
    for (const f of EDIT_FIELDS) {
      const label = document.createElement('label');
      label.textContent = f.label;
      const input = document.createElement('input');
      input.value =
        f.key === 'name' ? item.name
        : f.key === 'address' ? item.address
        : f.key === 'lat' ? String(item.lat)
        : f.key === 'lng' ? String(item.lng)
        : (item.csp_profile[f.key] ?? '');
      label.appendChild(input);
      form.appendChild(label);
      inputs.set(f.key, input);
    }
    const submit = document.createElement('button');
    submit.type = 'submit';
    submit.className = 'btn-primary';
    submit.textContent = 'Send for approval';
    const note = document.createElement('p');
    note.className = 'status';
    form.append(submit, note);

    editBtn.addEventListener('click', () => {
      form.hidden = !form.hidden;
    });
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      void (async () => {
        const changes: Record<string, string | number> = {};
        for (const f of EDIT_FIELDS) {
          const raw = inputs.get(f.key)!.value.trim();
          if (raw === '') continue;
          changes[f.key] = f.numeric ? Number(raw) : raw;
        }
        submit.disabled = true;
        note.textContent = 'Sending…';
        try {
          await createCspChangeRequest(item.csp_location_id, changes);
          note.textContent = '✅ Sent — pending Circle Head approval';
          form.querySelectorAll('input').forEach((i) => (i.disabled = true));
        } catch (err) {
          note.textContent = err instanceof ApiError ? err.message : 'Failed to send';
          submit.disabled = false;
        }
      })();
    });

    actions.append(directions, editBtn);
    el.append(head, addr, meta, actions, form);
    return el;
  }

  async function refresh(): Promise<void> {
    if (disposed) return;
    statusEl.textContent = 'Loading…';
    try {
      const res = await getDcCspDetails();
      if (disposed) return;
      statusEl.textContent = '';
      errorBox.hidden = true;
      emptyEl.hidden = res.items.length > 0;
      // Never-visited first, then stalest visits — the DC's priority order (spec §3).
      const sorted = [...res.items].sort((a, b) =>
        (a.last_visit_date ?? '0000') < (b.last_visit_date ?? '0000') ? -1 : 1,
      );
      cardsEl.replaceChildren(...sorted.map(card));
    } catch (err) {
      if (disposed) return;
      statusEl.textContent = '';
      cardsEl.replaceChildren();
      emptyEl.hidden = true;
      errorBox.hidden = false;
      errorMsg.textContent = err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Unexpected error';
    }
  }

  // Distance-from-here is best-effort: render immediately, re-render when a fix arrives.
  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        here = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        void refresh();
      },
      () => {},
      { timeout: 6000, maximumAge: 60000 },
    );
  }

  root.querySelector<HTMLButtonElement>('#cd-refresh')!.addEventListener('click', () => void refresh());
  root.querySelector<HTMLButtonElement>('#cd-retry')!.addEventListener('click', () => void refresh());
  void refresh();

  return () => {
    disposed = true;
  };
}
