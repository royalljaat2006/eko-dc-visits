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
import * as XLSX from 'xlsx';
import {
  ApiError,
  importCspAssignments,
  listAttendance,
  listCspAssignments,
  listLocations,
  transferCsp,
  type CspAssignment,
  type Location,
} from '../api/client.ts';
import { rowsFromSheetObjects } from '../lib/importRows.ts';
import { todayIstDate } from '../lib/format.ts';

const TEMPLATE_CSV = 'csp_code,dc_phone\nCSP-ND-1001,9800000001\nCSP-ND-1002,9800000006\n';

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
        <button id="wb-upload" type="button" class="btn-primary">Upload Excel</button>
        <a id="wb-template" download="csp-assignments-template.csv">Download template</a>
        <input id="wb-file" type="file" accept=".xlsx,.xls,.csv" hidden />
      </header>
      <p id="wb-import-report" class="status"></p>
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

  // ---- Bulk upload (C2 v0.6.0): sheet parsed in-browser, rows applied via
  // the audited transfer path; every row comes back accepted or rejected.
  const importReport = root.querySelector<HTMLElement>('#wb-import-report')!;
  const fileInput = root.querySelector<HTMLInputElement>('#wb-file')!;
  root.querySelector<HTMLAnchorElement>('#wb-template')!.href =
    `data:text/csv;charset=utf-8,${encodeURIComponent(TEMPLATE_CSV)}`;
  root.querySelector<HTMLButtonElement>('#wb-upload')!.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => {
    const file = fileInput.files?.[0];
    fileInput.value = ''; // allow re-selecting the same file
    if (!file) return;
    void (async () => {
      try {
        const workbook = XLSX.read(await file.arrayBuffer());
        const sheetName = workbook.SheetNames[0];
        const sheet = sheetName ? workbook.Sheets[sheetName] : undefined;
        if (!sheet) throw new Error('The file has no sheets');
        const { rows, errors } = rowsFromSheetObjects(
          XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: '' }),
        );
        if (rows.length === 0) throw new Error(errors.join(' · ') || 'No valid rows found');
        const skipped = errors.length > 0 ? ` (${errors.length} unreadable row${errors.length === 1 ? '' : 's'} skipped)` : '';
        if (!window.confirm(`Apply ${rows.length} assignment${rows.length === 1 ? '' : 's'} from "${file.name}"${skipped}?`)) return;

        const res = await importCspAssignments(rows);
        const s = res.summary;
        importReport.textContent =
          `Import: ${s.assigned} assigned · ${s.transferred} transferred · ${s.unchanged} unchanged · ${s.rejected} rejected` +
          (errors.length ? ` · ${errors.length} skipped before upload` : '');
        await refresh(false); // the server's answer is the truth
        // After refresh (which clears the error box on success), surface the
        // per-row rejections so they aren't lost.
        const rejected = res.results.filter((r) => r.result === 'rejected');
        if (rejected.length > 0 || errors.length > 0) {
          errorBox.hidden = false;
          errorMsg.textContent = [
            ...rejected.map((r) => `Row ${r.row} (${r.csp_code}): ${r.reason}`),
            ...errors,
          ].join(' — ');
        }
      } catch (err) {
        importReport.textContent = '';
        showError(err);
      }
    })();
  });

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
