/**
 * ADMIN OVERVIEW (C2 v0.5.0 /dashboard/overview) — the tenant cockpit for
 * CORPORATE_ADMIN and NATIONAL_HEAD: attendance, visits with geofence
 * outcomes, CSP assignment coverage, per-circle and per-DC rollups, banks.
 * One payload, every headline number; 10 s polling like every live surface.
 */
import { ApiError, getOverview, type OverviewResponse } from '../api/client.ts';
import { todayIstDate } from '../lib/format.ts';
import { attendanceChip } from '../lib/chips.ts';

const POLL_MS = 10_000;

export function renderOverviewView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Overview</h1>
        <label>Date (IST) <input id="ov-date" type="date" /></label>
      </header>
      <p id="ov-status" class="status" role="status"></p>
      <div id="ov-error" class="error-box" role="alert" hidden>
        <span id="ov-error-msg"></span>
        <button id="ov-retry" type="button">Retry</button>
      </div>
      <div id="ov-stats" class="cards"></div>

      <h2 class="section-title">Circles</h2>
      <table class="visits">
        <thead><tr><th>Circle</th><th>Circle Head</th><th>DCs</th><th>On duty</th><th>CSPs</th><th>Visits today</th><th>Flagged</th></tr></thead>
        <tbody id="ov-circles"></tbody>
      </table>

      <h2 class="section-title">DCs</h2>
      <table class="visits">
        <thead><tr><th>DC</th><th>Attendance</th><th>CSPs assigned</th><th>Visits today</th></tr></thead>
        <tbody id="ov-dcs"></tbody>
      </table>

      <p id="ov-banks" class="status"></p>
    </main>
  `;

  const dateEl = root.querySelector<HTMLInputElement>('#ov-date')!;
  const statusEl = root.querySelector<HTMLElement>('#ov-status')!;
  const errorBox = root.querySelector<HTMLElement>('#ov-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#ov-error-msg')!;
  const statsEl = root.querySelector<HTMLElement>('#ov-stats')!;
  const circlesEl = root.querySelector<HTMLTableSectionElement>('#ov-circles')!;
  const dcsEl = root.querySelector<HTMLTableSectionElement>('#ov-dcs')!;
  const banksEl = root.querySelector<HTMLElement>('#ov-banks')!;

  dateEl.value = todayIstDate();

  let timer: ReturnType<typeof setInterval> | null = null;
  let inflight: AbortController | null = null;
  let disposed = false;

  function stat(value: string, label: string, sub: string): HTMLElement {
    const card = document.createElement('article');
    card.className = 'score-card';
    const points = document.createElement('span');
    points.className = 'score-points';
    points.textContent = value;
    const name = document.createElement('span');
    name.className = 'score-points-label';
    name.textContent = label;
    const breakdown = document.createElement('p');
    breakdown.className = 'score-breakdown';
    breakdown.textContent = sub;
    const row = document.createElement('div');
    row.className = 'score-points-row';
    row.append(points, name);
    card.append(row, breakdown);
    return card;
  }

  function render(d: OverviewResponse): void {
    errorBox.hidden = true;

    statsEl.replaceChildren(
      stat(`${d.attendance.on_duty}/${d.attendance.total_dcs}`, 'DCs on duty', `${d.attendance.ended} ended · ${d.attendance.not_started} not started`),
      stat(String(d.visits.total), 'visits today', `${d.visits.geo_verified} geo-verified · ${d.visits.flagged} flagged · ${d.visits.unplanned} unplanned`),
      stat(`${d.csps.assigned}/${d.csps.total}`, 'CSPs assigned', `${d.csps.unassigned} unassigned · ${d.csps.coordinates_unverified} coords unverified`),
      stat(String(d.visits.late_sync), 'late-sync visits', 'evidence that arrived on a later day'),
    );

    circlesEl.replaceChildren(
      ...d.circles.map((c) => {
        const tr = document.createElement('tr');
        tr.append(
          td(c.circle_name),
          td(c.circle_head ?? '— no head assigned —'),
          td(String(c.dc_count)),
          td(String(c.on_duty)),
          td(String(c.csp_count)),
          td(String(c.visits_today)),
          td(String(c.flagged_today)),
        );
        return tr;
      }),
    );

    dcsEl.replaceChildren(
      ...d.assignments_by_dc.map((r) => {
        const tr = document.createElement('tr');
        const chip = attendanceChip(r.attendance);
        tr.append(td(r.dc_name), tdChip(chip.label, chip.className, chip.title), td(String(r.csp_count)), td(String(r.visits_today)));
        return tr;
      }),
    );

    banksEl.textContent = `Banks: ${d.banks.map((b) => `${b.name} (${b.code}) — ${b.csp_count} CSPs, ${b.status}`).join(' · ')}`;
  }

  async function refresh(showLoading: boolean): Promise<void> {
    if (disposed || !dateEl.value) return;
    inflight?.abort();
    const ac = new AbortController();
    inflight = ac;
    if (showLoading) statusEl.textContent = 'Loading…';
    try {
      const res = await getOverview(dateEl.value, ac.signal);
      if (disposed || ac.signal.aborted) return;
      statusEl.textContent = '';
      render(res);
    } catch (err) {
      if (disposed || ac.signal.aborted) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      statusEl.textContent = '';
      statsEl.replaceChildren();
      circlesEl.replaceChildren();
      dcsEl.replaceChildren();
      banksEl.textContent = '';
      errorBox.hidden = false;
      errorMsg.textContent = err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Unexpected error';
    }
  }

  function startPolling(): void {
    if (timer === null) timer = setInterval(() => void refresh(false), POLL_MS);
  }
  function stopPolling(): void {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }
  function onVisibility(): void {
    if (document.hidden) stopPolling();
    else {
      void refresh(false);
      startPolling();
    }
  }
  document.addEventListener('visibilitychange', onVisibility);
  dateEl.addEventListener('change', () => void refresh(true));
  root.querySelector<HTMLButtonElement>('#ov-retry')!.addEventListener('click', () => void refresh(true));

  void refresh(true);
  if (!document.hidden) startPolling();

  return () => {
    disposed = true;
    stopPolling();
    inflight?.abort();
    document.removeEventListener('visibilitychange', onVisibility);
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
