/**
 * SCORECARD (design 0002 — dc_score_v1): gamified, formula-transparent DC
 * cards. Celebration, never surveillance: the formula is printed on the page,
 * the DC sees exactly what their managers see (parity rule), and these points
 * never feed pay or enforcement.
 *
 * Scoping is SERVER-SIDE (C6): DC self, Circle Head circle, NH tenant-wide.
 */
import { ApiError, getSession, listScorecards, type ScorecardRow } from '../api/client.ts';
import { todayIstDate } from '../lib/format.ts';

const POLL_MS = 30_000;
/** Daily meter target: 3 geo-verified visits + on-time start + a 7-day streak. */
const METER_TARGET = 3 * 50 + 3 * 20 + 30 + 70;

const BADGE_LABELS: Record<string, string> = {
  EARLY_BIRD: '🌅 Early Bird',
  PERFECT_DAY: '⭐ Perfect Day',
  STREAK_3: '🔥 3-Day Streak',
  STREAK_7: '🏆 7-Day Streak',
};

export function renderScorecardView(root: HTMLElement): () => void {
  root.innerHTML = `
    <main class="day">
      <header class="topbar">
        <h1>Scorecard</h1>
        <label>Date (IST) <input id="sc-date" type="date" /></label>
      </header>
      <p id="sc-status" class="status" role="status"></p>
      <div id="sc-error" class="error-box" role="alert" hidden>
        <span id="sc-error-msg"></span>
        <button id="sc-retry" type="button">Retry</button>
      </div>
      <div id="sc-cards" class="cards"></div>
      <p id="sc-empty" class="empty" hidden>No DCs in your scope</p>
      <p class="formula-note">
        dc_score_v1 — every point is published (contracts/c7-kpis): visit +50 ·
        geo-verified +20 · on-time start (≤09:30 IST) +30 · streak day +10 (max 7).
        Scores celebrate work; they never affect pay.
      </p>
    </main>
  `;

  const dateEl = root.querySelector<HTMLInputElement>('#sc-date')!;
  const statusEl = root.querySelector<HTMLElement>('#sc-status')!;
  const errorBox = root.querySelector<HTMLElement>('#sc-error')!;
  const errorMsg = root.querySelector<HTMLElement>('#sc-error-msg')!;
  const cardsEl = root.querySelector<HTMLElement>('#sc-cards')!;
  const emptyEl = root.querySelector<HTMLElement>('#sc-empty')!;

  dateEl.value = todayIstDate();

  let timer: ReturnType<typeof setInterval> | null = null;
  let inflight: AbortController | null = null;
  let disposed = false;

  function card(row: ScorecardRow): HTMLElement {
    const el = document.createElement('article');
    el.className = 'score-card';

    const head = document.createElement('div');
    head.className = 'score-head';
    const name = document.createElement('span');
    name.className = 'score-name';
    name.textContent = row.dc_name;
    const streak = document.createElement('span');
    streak.className = 'score-streak';
    streak.textContent = row.streak_days > 0 ? `🔥 ${row.streak_days}-day streak` : 'No streak yet';
    head.append(name, streak);

    const pointsRow = document.createElement('div');
    pointsRow.className = 'score-points-row';
    const points = document.createElement('span');
    points.className = 'score-points';
    points.textContent = String(row.points);
    const label = document.createElement('span');
    label.className = 'score-points-label';
    label.textContent = 'points today';
    pointsRow.append(points, label);

    const meter = document.createElement('div');
    meter.className = 'score-meter';
    const fill = document.createElement('div');
    fill.style.width = `${Math.min(100, Math.round((row.points / METER_TARGET) * 100))}%`;
    meter.appendChild(fill);

    const breakdown = document.createElement('p');
    breakdown.className = 'score-breakdown';
    breakdown.textContent =
      `${row.visits_done} visit${row.visits_done === 1 ? '' : 's'} · ` +
      `${row.geo_verified_visits} geo-verified · ` +
      (row.on_time_start ? 'on-time start' : 'no on-time start');

    const badges = document.createElement('div');
    badges.className = 'badges';
    for (const b of row.badges) {
      const pill = document.createElement('span');
      pill.className = 'badge';
      pill.textContent = BADGE_LABELS[b] ?? b;
      badges.appendChild(pill);
    }

    el.append(head, pointsRow, meter, breakdown, badges);
    // Spec §3: "My Dashboard" — only on the logged-in user's OWN card, from
    // their user record (never another DC's link).
    const session = getSession();
    if (session && row.dc_user_id === session.user.id && session.user.dashboard_url) {
      const link = document.createElement('a');
      link.href = session.user.dashboard_url;
      link.target = '_blank';
      link.rel = 'noopener';
      link.className = 'badge';
      link.textContent = '📊 My Dashboard';
      el.appendChild(link);
    }
    return el;
  }

  async function refresh(showLoading: boolean): Promise<void> {
    if (disposed || !dateEl.value) return;
    inflight?.abort();
    const ac = new AbortController();
    inflight = ac;
    if (showLoading) statusEl.textContent = 'Loading…';
    try {
      const res = await listScorecards(dateEl.value, ac.signal);
      if (disposed || ac.signal.aborted) return;
      statusEl.textContent = '';
      errorBox.hidden = true;
      emptyEl.hidden = res.items.length > 0;
      // Render exactly what the API returned, highest points first (a circle's
      // progress view, not a pay-linked ranking — design 0002 §4).
      cardsEl.replaceChildren(...[...res.items].sort((a, b) => b.points - a.points).map(card));
    } catch (err) {
      if (disposed || ac.signal.aborted) return;
      if (err instanceof DOMException && err.name === 'AbortError') return;
      statusEl.textContent = '';
      cardsEl.replaceChildren();
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
  root.querySelector<HTMLButtonElement>('#sc-retry')!.addEventListener('click', () => void refresh(true));

  void refresh(true);
  if (!document.hidden) startPolling();

  return () => {
    disposed = true;
    stopPolling();
    inflight?.abort();
    document.removeEventListener('visibilitychange', onVisibility);
  };
}
