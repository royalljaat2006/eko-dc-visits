/**
 * ADMIN "All CSPs" — every CSP in scope with its code and address, in the order
 * of Eko's calling sheet (the server sorts by sheet row; this view never
 * re-sorts). The sheet fills addresses in gradually, so a missing address is
 * shown as "Address pending", not hidden. CSP operators are anonymous (the code
 * is the only identity); the DC is a named Eko official, so name + phone show.
 * Search/filters are display-only conveniences over the server-scoped list.
 */
import { ApiError, listDashboardCsps, type DashboardCspRow } from '../api/client.ts';

const ALL = '';

export function renderAllCspsView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>All CSPs</h1>
        <input id="ac-search" type="search" placeholder="Search code, address, district, DC…" size="32" />
        <select id="ac-circle" aria-label="Circle"></select>
        <select id="ac-dc" aria-label="District Coordinator"></select>
        <label><input id="ac-pending" type="checkbox" /> Address pending only</label>
        <button id="ac-refresh" type="button">Refresh</button>
      </header>
      <p id="ac-status" class="status" role="status"></p>
      <div id="ac-error" class="error-box" role="alert" hidden>
        <span id="ac-error-msg"></span>
        <button id="ac-retry" type="button">Retry</button>
      </div>
      <p id="ac-summary" class="status"></p>
      <table class="visits">
        <thead>
          <tr><th>#</th><th>CSP code</th><th>Address</th><th>District</th><th>State</th><th>Circle</th><th>DC</th><th>DC mobile</th></tr>
        </thead>
        <tbody id="ac-body"></tbody>
      </table>
      <p id="ac-empty" class="empty" hidden>No CSPs match</p>
    </main>
  `;

  const q = <T extends HTMLElement>(sel: string): T => root.querySelector<T>(sel)!;
  const search = q<HTMLInputElement>('#ac-search');
  const circleSel = q<HTMLSelectElement>('#ac-circle');
  const dcSel = q<HTMLSelectElement>('#ac-dc');
  const pendingBox = q<HTMLInputElement>('#ac-pending');
  const statusEl = q<HTMLElement>('#ac-status');
  const summaryEl = q<HTMLElement>('#ac-summary');
  const errorBox = q<HTMLElement>('#ac-error');
  const errorMsg = q<HTMLElement>('#ac-error-msg');
  const tbody = q<HTMLTableSectionElement>('#ac-body');
  const emptyEl = q<HTMLElement>('#ac-empty');

  let rows: DashboardCspRow[] = [];
  let inflight: AbortController | null = null;
  let disposed = false;

  function fillSelect(sel: HTMLSelectElement, label: string, values: string[]): void {
    const keep = sel.value;
    sel.replaceChildren(
      new Option(label, ALL),
      ...[...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b)).map((v) => new Option(v, v)),
    );
    sel.value = [...sel.options].some((o) => o.value === keep) ? keep : ALL;
  }

  function render(): void {
    const needle = search.value.trim().toLowerCase();
    const shown = rows.filter((r) => {
      if (circleSel.value && r.circle !== circleSel.value) return false;
      if (dcSel.value && r.dc_name !== dcSel.value) return false;
      if (pendingBox.checked && r.address) return false;
      if (!needle) return true;
      return [r.code, r.address, r.district, r.state, r.dc_name, r.dc_phone].some((v) =>
        (v ?? '').toLowerCase().includes(needle),
      );
    });
    const withAddress = rows.filter((r) => r.address).length;
    summaryEl.textContent =
      rows.length === 0
        ? ''
        : `${shown.length} of ${rows.length} CSPs shown · ${withAddress} with address · ${rows.length - withAddress} address pending`;
    emptyEl.hidden = shown.length > 0;
    tbody.replaceChildren(
      ...shown.map((r, i) => {
        const tr = document.createElement('tr');
        tr.append(
          td(String(r.sheet_row ?? i + 1)),
          td(r.code),
          addressCell(r.address),
          td(r.district ?? '—'),
          td(r.state ?? '—'),
          td(r.circle ?? '—'),
          td(r.dc_name ?? 'Unassigned'),
          td(r.dc_phone ?? '—'),
        );
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
      const res = await listDashboardCsps(ac.signal);
      if (disposed || ac.signal.aborted) return;
      rows = res.items;
      statusEl.textContent = '';
      errorBox.hidden = true;
      fillSelect(circleSel, 'All circles', rows.map((r) => r.circle ?? ''));
      fillSelect(dcSel, 'All DCs', rows.map((r) => r.dc_name ?? ''));
      render();
    } catch (err) {
      if (disposed || ac.signal.aborted) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      statusEl.textContent = '';
      tbody.replaceChildren();
      emptyEl.hidden = true;
      errorBox.hidden = false;
      errorMsg.textContent = err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Unexpected error';
    }
  }

  for (const el of [search, circleSel, dcSel, pendingBox]) el.addEventListener('input', render);
  q<HTMLButtonElement>('#ac-refresh').addEventListener('click', () => void refresh(true));
  q<HTMLButtonElement>('#ac-retry').addEventListener('click', () => void refresh(true));
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

function addressCell(address: string | null): HTMLTableCellElement {
  const cell = document.createElement('td');
  if (address) {
    cell.textContent = address;
  } else {
    cell.textContent = 'Address pending';
    cell.style.opacity = '0.55';
    cell.style.fontStyle = 'italic';
  }
  return cell;
}
