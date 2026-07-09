/**
 * ATTENDANCE BOARD (design 0001 §7): one row per DC in the caller's scope,
 * including NOT_STARTED — polling GET /dashboard/attendance every 10 s.
 *
 * SECURITY BOUNDARY NOTE — no client-side filtering, ever. Scoping is
 * SERVER-SIDE (C6: NH + HR/Admin tenant-wide, Circle Head circle, DC self);
 * this view renders exactly what the API returns.
 */
import { ApiError, listAttendance, type AttendanceRow } from '../api/client.ts';
import { formatIstDateTime, todayIstDate } from '../lib/format.ts';
import { attendanceChip } from '../lib/chips.ts';

const POLL_MS = 10_000;

export function renderAttendanceView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Attendance Board</h1>
        <label>Date (IST) <input id="att-date" type="date" /></label>
      </header>
      <p id="att-status" class="status" role="status"></p>
      <div id="att-error" class="error-box" role="alert" hidden>
        <span id="att-error-msg"></span>
        <button id="att-retry" type="button">Retry</button>
      </div>
      <p id="att-summary" class="status"></p>
      <table class="visits">
        <thead>
          <tr><th>DC</th><th>Status</th><th>Start (IST)</th><th>End (IST)</th></tr>
        </thead>
        <tbody id="att-body"></tbody>
      </table>
      <p id="att-empty" class="empty" hidden>No DCs in your scope</p>
    </main>
  `;

  const dateEl = root.querySelector<HTMLInputElement>('#att-date')!;
  const statusEl = root.querySelector<HTMLElement>('#att-status')!;
  const summaryEl = root.querySelector<HTMLElement>('#att-summary')!;
  const errorBox = root.querySelector<HTMLElement>('#att-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#att-error-msg')!;
  const tbody = root.querySelector<HTMLTableSectionElement>('#att-body')!;
  const emptyEl = root.querySelector<HTMLElement>('#att-empty')!;

  dateEl.value = todayIstDate();

  let timer: ReturnType<typeof setInterval> | null = null;
  let inflight: AbortController | null = null;
  let disposed = false;

  function renderRows(rows: readonly AttendanceRow[]): void {
    errorBox.hidden = true;
    emptyEl.hidden = rows.length > 0;

    const counts = { ON_DUTY: 0, ENDED: 0, NOT_STARTED: 0 };
    for (const r of rows) counts[r.status] += 1;
    summaryEl.textContent =
      rows.length === 0 ? '' : `${rows.length} DCs — ${counts.ON_DUTY} on duty · ${counts.ENDED} ended · ${counts.NOT_STARTED} not started`;

    tbody.replaceChildren(
      ...rows.map((r) => {
        const tr = document.createElement('tr');
        const chip = attendanceChip(r.status);
        tr.append(td(r.dc_name), tdChip(chip.label, chip.className, chip.title), td(formatIstDateTime(r.started_at)), td(formatIstDateTime(r.ended_at)));
        return tr;
      }),
    );
  }

  async function refresh(showLoading: boolean): Promise<void> {
    if (disposed || !dateEl.value) return;
    inflight?.abort();
    const ac = new AbortController();
    inflight = ac;
    if (showLoading) statusEl.textContent = 'Loading…';
    try {
      const res = await listAttendance(dateEl.value, ac.signal);
      if (disposed || ac.signal.aborted) return;
      statusEl.textContent = `Last updated ${formatIstDateTime(new Date().toISOString())} IST`;
      renderRows(res.items);
    } catch (err) {
      if (disposed || ac.signal.aborted) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      statusEl.textContent = '';
      summaryEl.textContent = '';
      tbody.replaceChildren(); // never show stale data alongside an error
      emptyEl.hidden = true;
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
  root.querySelector<HTMLButtonElement>('#att-retry')!.addEventListener('click', () => void refresh(true));

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
