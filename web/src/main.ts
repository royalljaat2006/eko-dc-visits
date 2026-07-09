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

type Route = '#/login' | '#/day' | '#/attendance' | '#/csps';

const app = document.querySelector<HTMLElement>('#app')!;
let teardown: (() => void) | null = null;

function tabsFor(role: Role): Array<{ hash: Route; label: string }> {
  const visits = { hash: '#/day' as const, label: 'Visits' };
  const attendance = { hash: '#/attendance' as const, label: 'Attendance' };
  const csps = { hash: '#/csps' as const, label: 'CSP Workbench' };
  switch (role) {
    case 'HR_ADMIN':
      return [attendance]; // C6: attendance-only visibility
    case 'CIRCLE_HEAD':
    case 'CORPORATE_ADMIN':
      return [visits, attendance, csps];
    default:
      return [visits, attendance];
  }
}

function defaultRoute(role: Role): Route {
  return role === 'NATIONAL_HEAD' || role === 'HR_ADMIN' ? '#/attendance' : '#/day';
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
  const active = tabs.some((t) => t.hash === requested) ? requested : defaultRoute(role);

  // Shell: tab nav + logout, then the active view below.
  app.innerHTML = `
    <nav class="tabs">
      ${tabs
        .map(
          (t) =>
            `<a href="${t.hash}" class="tab${t.hash === active ? ' tab-active' : ''}">${t.label}</a>`,
        )
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
