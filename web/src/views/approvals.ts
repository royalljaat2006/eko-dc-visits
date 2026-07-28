/**
 * APPROVALS (spec §4) — the Circle Head's queue of DC-proposed CSP edits,
 * scoped strictly to their own circle (Admin sees tenant-wide). Each card
 * shows old vs proposed side-by-side; rejection carries a reason back to
 * the DC. Approval applies the edit to the CSP master immediately.
 */
import { ApiError, decideCspChangeRequest, listCspChangeRequests, type CspChangeRequest } from '../api/client.ts';
import { formatIstDateTime } from '../lib/format.ts';

export function renderApprovalsView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Approvals</h1>
        <button id="ap-refresh" type="button">Refresh</button>
      </header>
      <p id="ap-status" class="status" role="status"></p>
      <div id="ap-error" class="error-box" role="alert" hidden>
        <span id="ap-error-msg"></span>
        <button id="ap-retry" type="button">Retry</button>
      </div>
      <div id="ap-cards" class="cards"></div>
      <p id="ap-empty" class="empty" hidden>No pending change requests 🎉</p>
    </main>
  `;

  const statusEl = root.querySelector<HTMLElement>('#ap-status')!;
  const errorBox = root.querySelector<HTMLElement>('#ap-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#ap-error-msg')!;
  const cardsEl = root.querySelector<HTMLElement>('#ap-cards')!;
  const emptyEl = root.querySelector<HTMLElement>('#ap-empty')!;

  let disposed = false;

  function card(r: CspChangeRequest): HTMLElement {
    const el = document.createElement('article');
    el.className = 'score-card';

    const head = document.createElement('div');
    head.className = 'score-head';
    const name = document.createElement('span');
    name.className = 'score-name';
    name.textContent = `${r.csp_name ?? 'CSP'} (${r.csp_code ?? '—'})`;
    head.appendChild(name);

    const meta = document.createElement('p');
    meta.className = 'score-breakdown';
    meta.textContent = `Proposed by ${r.requested_by_name ?? 'a DC'} · ${formatIstDateTime(r.created_at)} IST`;

    // Old vs proposed, side-by-side (spec §3 suggestion).
    const table = document.createElement('table');
    table.className = 'visits cr-diff';
    const thead = document.createElement('thead');
    thead.innerHTML = '<tr><th>Field</th><th>Current</th><th>Proposed</th></tr>';
    const tbody = document.createElement('tbody');
    for (const [field, ch] of Object.entries(r.changes)) {
      const tr = document.createElement('tr');
      const tdField = document.createElement('td');
      tdField.textContent = field;
      const tdOld = document.createElement('td');
      tdOld.textContent = ch.old === null || ch.old === '' ? '—' : String(ch.old);
      const tdNew = document.createElement('td');
      tdNew.textContent = String(ch.new);
      tdNew.className = 'cr-new';
      tr.append(tdField, tdOld, tdNew);
      tbody.appendChild(tr);
    }
    table.append(thead, tbody);

    const actions = document.createElement('div');
    actions.className = 'badges';
    const approve = document.createElement('button');
    approve.type = 'button';
    approve.className = 'btn-primary';
    approve.textContent = 'Approve';
    const reject = document.createElement('button');
    reject.type = 'button';
    reject.textContent = 'Reject…';

    const decide = (decision: 'APPROVED' | 'REJECTED', reason?: string): void => {
      approve.disabled = reject.disabled = true;
      void (async () => {
        try {
          await decideCspChangeRequest(r.id, decision, reason);
          await refresh();
        } catch (err) {
          errorBox.hidden = false;
          errorMsg.textContent = err instanceof ApiError ? err.message : 'Decision failed';
          approve.disabled = reject.disabled = false;
        }
      })();
    };
    approve.addEventListener('click', () => decide('APPROVED'));
    reject.addEventListener('click', () => {
      const reason = window.prompt('Rejection reason (sent back to the DC):') ?? '';
      if (reason.trim().length === 0) return; // a rejection without a reason helps nobody
      decide('REJECTED', reason.trim());
    });

    actions.append(approve, reject);
    el.append(head, meta, table, actions);
    return el;
  }

  async function refresh(): Promise<void> {
    if (disposed) return;
    statusEl.textContent = 'Loading…';
    try {
      const res = await listCspChangeRequests('PENDING');
      if (disposed) return;
      statusEl.textContent = '';
      errorBox.hidden = true;
      emptyEl.hidden = res.items.length > 0;
      cardsEl.replaceChildren(...res.items.map(card));
    } catch (err) {
      if (disposed) return;
      statusEl.textContent = '';
      cardsEl.replaceChildren();
      emptyEl.hidden = true;
      errorBox.hidden = false;
      errorMsg.textContent = err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Unexpected error';
    }
  }

  root.querySelector<HTMLButtonElement>('#ap-refresh')!.addEventListener('click', () => void refresh());
  root.querySelector<HTMLButtonElement>('#ap-retry')!.addEventListener('click', () => void refresh());
  void refresh();

  return () => {
    disposed = true;
  };
}
