/**
 * App shell: hash router + role-aware tab nav + 401 → login redirect.
 *
 * Tabs shown per role (display only — every endpoint is server-scoped via C6;
 * hiding a tab is UX, never the security boundary):
 *   DC / CIRCLE_HEAD / NATIONAL_HEAD / CORPORATE_ADMIN → Visits, Attendance
 *   CIRCLE_HEAD / CORPORATE_ADMIN                      → + CSP Workbench
 *   HR_ADMIN                                           → Attendance only
 */
import './style.css';
import { clearSession, getSession, setUnauthorizedHandler, type Role } from './api/client.ts';
import { renderLogin } from './views/login.ts';
import { renderDayView } from './views/day.ts';
import { renderAttendanceView } from './views/attendance.ts';
import { renderWorkbenchView } from './views/workbench.ts';
import { renderScorecardView } from './views/scorecard.ts';
import { renderOverviewView } from './views/overview.ts';
import { renderCspDetailsView } from './views/csp-details.ts';
import { renderApprovalsView } from './views/approvals.ts';
import { isDayStarted } from './lib/gate.ts';
import ekoLogo from './assets/eko-logo.jpeg';

type Route = '#/login' | '#/day' | '#/attendance' | '#/csps' | '#/scorecard' | '#/overview' | '#/my-csps' | '#/approvals';

const app = document.querySelector<HTMLElement>('#app')!;
let teardown: (() => void) | null = null;

function tabsFor(role: Role): Array<{ hash: Route; label: string }> {
  const visits = { hash: '#/day' as const, label: 'Visits' };
  const attendance = { hash: '#/attendance' as const, label: 'Attendance' };
  const csps = { hash: '#/csps' as const, label: 'CSP Workbench' };
  const scorecard = { hash: '#/scorecard' as const, label: 'Scorecard' };
  const overview = { hash: '#/overview' as const, label: 'Overview' };
  const myCsps = { hash: '#/my-csps' as const, label: 'My CSPs' };
  const approvals = { hash: '#/approvals' as const, label: 'Approvals' };
  switch (role) {
    case 'HR_ADMIN':
      return [attendance]; // C6: attendance-only visibility
    case 'CORPORATE_ADMIN':
      return [overview, visits, attendance, csps, approvals, scorecard];
    case 'NATIONAL_HEAD':
      return [overview, visits, attendance, scorecard];
    case 'CIRCLE_HEAD':
      return [attendance, visits, csps, approvals, scorecard];
    default:
      // Spec (DC): Attendance FIRST; everything else locked until day start.
      return [attendance, visits, myCsps, scorecard];
  }
}

/** Spec: DC sections other than Attendance are disabled until the day starts. */
function isGated(role: Role, hash: Route): boolean {
  return role === 'DC' && hash !== '#/attendance' && !isDayStarted();
}

function defaultRoute(role: Role): Route {
  if (role === 'CORPORATE_ADMIN' || role === 'NATIONAL_HEAD') return '#/overview';
  if (role === 'DC' || role === 'CIRCLE_HEAD' || role === 'HR_ADMIN') return '#/attendance';
  return '#/day';
}

function navigate(hash: Route): void {
  if (location.hash !== hash) {
    location.hash = hash; // triggers hashchange → route()
  } else {
    route();
  }
}

function route(): void {
  teardown?.();
  teardown = null;

  const session = getSession();
  if (!session || location.hash === '#/login') {
    teardown = renderLogin(app, () => navigate(defaultRoute(getSession()?.user.role ?? 'DC')));
    if (location.hash !== '#/login') location.hash = '#/login';
    return;
  }

  const role = session.user.role;
  const tabs = tabsFor(role);
  const requested = (location.hash || defaultRoute(role)) as Route;
  let active = tabs.some((t) => t.hash === requested) ? requested : defaultRoute(role);
  if (isGated(role, active)) active = '#/attendance'; // enforced ordering, not just visual

  // Shell: tab nav + logout, then the active view below.
  app.innerHTML = `
    <nav class="tabs">
      <img class="nav-logo" src="${ekoLogo}" alt="Eko" />
      ${tabs
        .map((t) => {
          const gated = isGated(role, t.hash);
          const cls = `tab${t.hash === active ? ' tab-active' : ''}${gated ? ' tab-disabled' : ''}`;
          const title = gated ? ' title="Mark attendance first"' : '';
          return `<a href="${gated ? '#/attendance' : t.hash}" class="${cls}"${title}>${t.label}</a>`;
        })
        .join('')}
      <span class="tabs-who">${escapeHtml(`${session.user.name}`)}</span>
      <button id="nav-logout" type="button" class="tab-logout">Log out</button>
    </nav>
    <div id="view"></div>
  `;
  app.querySelector<HTMLButtonElement>('#nav-logout')!.addEventListener('click', () => {
    clearSession();
    navigate('#/login');
  });

  const view = app.querySelector<HTMLElement>('#view')!;
  if (active === '#/attendance') {
    teardown = renderAttendanceView(view);
  } else if (active === '#/csps') {
    teardown = renderWorkbenchView(view);
  } else if (active === '#/scorecard') {
    teardown = renderScorecardView(view);
  } else if (active === '#/overview') {
    teardown = renderOverviewView(view);
  } else if (active === '#/my-csps') {
    teardown = renderCspDetailsView(view);
  } else if (active === '#/approvals') {
    teardown = renderApprovalsView(view);
  } else {
    teardown = renderDayView(view);
  }
  if (location.hash !== active) location.hash = active;
}

// Any authenticated call that hits 401 clears the session and lands here (client.ts).
setUnauthorizedHandler(() => navigate('#/login'));

window.addEventListener('hashchange', route);
route();

function escapeHtml(s: string): string {
  return s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
