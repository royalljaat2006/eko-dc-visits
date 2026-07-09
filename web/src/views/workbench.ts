/**
 * CIRCLE HEAD WORKBENCH v1 (design 0001 §6): the circle's CSP assignments with
 * add/transfer actions. The M3 optimizer (clustering + drag-and-drop map) will
 * extend this view; v1 is the manual assign/transfer capability.
 *
 * Data: /master-data/csp-assignments (circle's active assignments) joined
 * client-side with /master-data/locations for names — a display join only;
 * both collections arrive server-scoped (C6). The DC roster comes from
 * /dashboard/attendance, which for a Circle Head returns exactly the circle's
 * DCs. Transfers POST /circle/csp-assignments/transfer; the server enforces
 * every guardrail (role, in-circle target) — this UI never pre-filters beyond
 * what the API returned.
 */
import {
  ApiError,
  listAttendance,
  listCspAssignments,
  listLocations,
  transferCsp,
  type CspAssignment,
  type Location,
} from '../api/client.ts';
import { todayIstDate } from '../lib/format.ts';

interface Roster {
  dc_user_id: string;
  dc_name: string;
}

export function renderWorkbenchView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>CSP Workbench</h1>
        <button id="wb-refresh" type="button">Refresh</button>
      </header>
      <p id="wb-status" class="status" role="status"></p>
      <div id="wb-error" class="error-box" role="alert" hidden>
        <span id="wb-error-msg"></span>
        <button id="wb-retry" type="button">Retry</button>
      </div>
      <p id="wb-summary" class="status"></p>
      <table class="visits">
        <thead>
          <tr><th>CSP</th><th>Assigned DC</th><th>Since</th><th>Reason</th><th>Transfer to</th><th></th></tr>
        </thead>
        <tbody id="wb-body"></tbody>
      </table>
      <p id="wb-empty" class="empty" hidden>No CSP assignments in your circle</p>
    </main>
  `;

  const statusEl = root.querySelector<HTMLElement>('#wb-status')!;
  const summaryEl = root.querySelector<HTMLElement>('#wb-summary')!;
  const errorBox = root.querySelector<HTMLElement>('#wb-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#wb-error-msg')!;
  const tbody = root.querySelector<HTMLTableSectionElement>('#wb-body')!;
  const emptyEl = root.querySelector<HTMLElement>('#wb-empty')!;

  let inflight: AbortController | null = null;
  let disposed = false;

  function showError(err: unknown): void {
    statusEl.textContent = '';
    errorBox.hidden = false;
    errorMsg.textContent = err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Unexpected error';
  }

  function renderRows(assignments: CspAssignment[], locations: Location[], roster: Roster[]): void {
    errorBox.hidden = true;
    emptyEl.hidden = assignments.length > 0;

    const locById = new Map(locations.map((l) => [l.id, l]));
    const dcName = new Map(roster.map((r) => [r.dc_user_id, r.dc_name]));

    // Per-DC load summary — the number a Circle Head balances routes around.
    const perDc = new Map<string, number>();
    for (const a of assignments) perDc.set(a.dc_user_id, (perDc.get(a.dc_user_id) ?? 0) + 1);
    summaryEl.textContent =
      assignments.length === 0
        ? ''
        : `${assignments.length} CSPs — ` +
          [...perDc.entries()].map(([dc, n]) => `${dcName.get(dc) ?? dc}: ${n}`).join(' · ');

    tbody.replaceChildren(
      ...assignments.map((a) => {
        const tr = document.createElement('tr');
        const loc = locById.get(a.csp_location_id);
        tr.append(
          td(loc ? `${loc.name} (${loc.code})` : a.csp_location_id),
          td(dcName.get(a.dc_user_id) ?? a.dc_user_id),
          td(a.valid_from),
          td(a.reason),
        );

        const select = document.createElement('select');
        for (const r of roster) {
          if (r.dc_user_id === a.dc_user_id) continue; // a transfer targets a different DC
          const opt = document.createElement('option');
          opt.value = r.dc_user_id;
          opt.textContent = r.dc_name;
          select.appendChild(opt);
        }
        const selectCell = document.createElement('td');
        selectCell.appendChild(select);

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.textContent = 'Transfer';
        btn.disabled = select.options.length === 0;
        btn.addEventListener('click', () => {
          void (async () => {
            if (!select.value) return;
            btn.disabled = true;
            btn.textContent = 'Transferring…';
            try {
              await transferCsp(a.csp_location_id, select.value, 'TRANSFER');
              await refresh(false); // re-pull; the server's answer is the truth
            } catch (err) {
              showError(err);
              btn.disabled = false;
              btn.textContent = 'Transfer';
            }
          })();
        });
        const btnCell = document.createElement('td');
        btnCell.appendChild(btn);

        tr.append(selectCell, btnCell);
        return tr;
      }),
    );
  }

  async function refresh(showLoading: boolean): Promise<void> {
    if (disposed) return;
    inflight?.abort();
    const ac = new AbortController();
    inflight = ac;
    if (showLoading) statusEl.textContent = 'Loading…';
    try {
      const [assignments, locations, attendance] = await Promise.all([
        listCspAssignments(ac.signal),
        listLocations(ac.signal),
        listAttendance(todayIstDate(), ac.signal), // the circle's DC roster (C6-scoped)
      ]);
      if (disposed || ac.signal.aborted) return;
      statusEl.textContent = '';
      renderRows(assignments.items, locations.items, attendance.items);
    } catch (err) {
      if (disposed || ac.signal.aborted) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      tbody.replaceChildren();
      emptyEl.hidden = true;
      showError(err);
    }
  }

  root.querySelector<HTMLButtonElement>('#wb-refresh')!.addEventListener('click', () => void refresh(true));
  root.querySelector<HTMLButtonElement>('#wb-retry')!.addEventListener('click', () => void refresh(true));
  void refresh(true);

  return () => {
    disposed = true;
    inflight?.abort();
  };
}

function td(text: string): HTMLTableCellElement {
  const cell = document.createElement('td');
  cell.textContent = text;
  return cell;
}
