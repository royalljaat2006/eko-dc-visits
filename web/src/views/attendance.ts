/**
 * ATTENDANCE BOARD (design 0001 §7): one row per DC in the caller's scope,
 * including NOT_STARTED — polling GET /dashboard/attendance every 10 s.
 *
 * SECURITY BOUNDARY NOTE — no client-side filtering, ever. Scoping is
 * SERVER-SIDE (C6: NH + HR/Admin tenant-wide, Circle Head circle, DC self);
 * this view renders exactly what the API returns.
 */
import { ApiError, getSession, listAttendance, submitAttendance, type AttendanceRow } from '../api/client.ts';
import { formatIstDateTime, todayIstDate } from '../lib/format.ts';
import { attendanceChip } from '../lib/chips.ts';
import { setDayStarted } from '../lib/gate.ts';

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
      <div id="att-myday" class="score-card myday" hidden>
        <div class="score-head">
          <span class="score-name">My day</span>
          <span id="att-myday-chip"></span>
        </div>
        <p id="att-myday-meta" class="score-breakdown"></p>
        <div class="badges">
          <button id="att-checkin" type="button" class="btn-primary" hidden>✅ Check In (Start Day)</button>
          <button id="att-checkout" type="button" hidden>🌙 End Day</button>
        </div>
        <p class="hint">Attendance must be marked before logging visits. No End Day by 21:00 IST auto-closes the day — "not confirmed by user".</p>
      </div>
      <p id="att-summary" class="status"></p>
      <table class="visits">
        <thead>
          <tr><th>DC</th><th>Status</th><th>Hours</th><th title="Provisional — GPS straight-line estimate, not for reimbursement">KM today</th><th>Start (IST)</th><th>End (IST)</th></tr>
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

  const session = getSession();
  const mydayEl = root.querySelector<HTMLElement>('#att-myday')!;
  const mydayChip = root.querySelector<HTMLElement>('#att-myday-chip')!;
  const mydayMeta = root.querySelector<HTMLElement>('#att-myday-meta')!;
  const checkinBtn = root.querySelector<HTMLButtonElement>('#att-checkin')!;
  const checkoutBtn = root.querySelector<HTMLButtonElement>('#att-checkout')!;
  const canMarkOwn = session?.user.role === 'DC' || session?.user.role === 'CIRCLE_HEAD';

  function renderMyDay(rows: readonly AttendanceRow[]): void {
    if (!canMarkOwn || dateEl.value !== todayIstDate()) {
      mydayEl.hidden = true;
      return;
    }
    mydayEl.hidden = false;
    const self = rows.find((r) => r.dc_user_id === session!.user.id);
    const status = self?.status ?? 'NOT_STARTED';
    const chip = attendanceChip(status);
    mydayChip.innerHTML = '';
    const span = document.createElement('span');
    span.className = chip.className;
    span.textContent = chip.label;
    if (chip.title) span.title = chip.title;
    mydayChip.appendChild(span);
    mydayMeta.textContent = self?.started_at
      ? `Started ${formatIstDateTime(self.started_at)} IST` +
        (self.ended_at ? ` · ended ${formatIstDateTime(self.ended_at)} IST` : '') +
        (self.hours_worked !== null && self.hours_worked !== undefined ? ` · ${self.hours_worked} h` : '') +
        (self.km_today !== undefined ? ` · ${self.km_today} km (provisional)` : '')
      : 'Mark your attendance to start the day.';
    checkinBtn.hidden = status !== 'NOT_STARTED';
    checkoutBtn.hidden = status !== 'ON_DUTY';
    // Spec: attendance-first — unlock the other tabs once the day started (DC gate).
    setDayStarted(status !== 'NOT_STARTED');
  }

  function renderRows(rows: readonly AttendanceRow[]): void {
    errorBox.hidden = true;
    emptyEl.hidden = rows.length > 0;
    renderMyDay(rows);

    const counts = { ON_DUTY: 0, ENDED: 0, NOT_STARTED: 0, AUTO_CLOSED: 0 };
    for (const r of rows) counts[r.status] += 1;
    summaryEl.textContent =
      rows.length === 0 ? '' : `${rows.length} DCs — ${counts.ON_DUTY} on duty · ${counts.ENDED} ended · ${counts.AUTO_CLOSED} auto-closed · ${counts.NOT_STARTED} not started · ${Math.round(rows.reduce((s, r) => s + (r.km_today ?? 0), 0) * 10) / 10} km covered (provisional)`;

    tbody.replaceChildren(
      ...rows.map((r) => {
        const tr = document.createElement('tr');
        const chip = attendanceChip(r.status);
        tr.append(
          td(r.dc_name),
          tdChip(chip.label, chip.className, chip.title),
          td(r.hours_worked === null || r.hours_worked === undefined ? '—' : `${r.hours_worked} h`),
          td(r.km_today === undefined ? '—' : `${r.km_today} km`),
          td(formatIstDateTime(r.started_at)),
          td(formatIstDateTime(r.ended_at)),
        );
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
  async function mark(kind: 'START' | 'END', btn: HTMLButtonElement): Promise<void> {
    btn.disabled = true;
    statusEl.textContent = kind === 'START' ? 'Checking in… (GPS is optional)' : 'Ending your day…';
    try {
      await submitAttendance(kind);
      await refresh(false);
      window.dispatchEvent(new HashChangeEvent('hashchange')); // re-render shell so gated tabs unlock
    } catch (err) {
      errorBox.hidden = false;
      errorMsg.textContent = err instanceof ApiError ? err.message : 'Could not record attendance';
    } finally {
      btn.disabled = false;
    }
  }
  checkinBtn.addEventListener('click', () => void mark('START', checkinBtn));
  checkoutBtn.addEventListener('click', () => void mark('END', checkoutBtn));

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
