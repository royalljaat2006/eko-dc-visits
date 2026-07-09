/**
 * App shell: hash router (#/login, #/day) + 401 → login redirect.
 */
import './style.css';
import { getSession, setUnauthorizedHandler } from './api/client.ts';
import { renderLogin } from './views/login.ts';
import { renderDayView } from './views/day.ts';

const app = document.querySelector<HTMLElement>('#app')!;
let teardown: (() => void) | null = null;

function navigate(hash: '#/login' | '#/day'): void {
  if (location.hash !== hash) {
    location.hash = hash; // triggers hashchange → route()
  } else {
    route();
  }
}

function route(): void {
  teardown?.();
  teardown = null;

  const authed = getSession() !== null;
  const hash = location.hash || (authed ? '#/day' : '#/login');

  if (!authed || hash === '#/login') {
    teardown = renderLogin(app, () => navigate('#/day'));
    if (location.hash !== '#/login') location.hash = '#/login';
    return;
  }
  // Default authed route
  teardown = renderDayView(app, () => navigate('#/login'));
  if (location.hash !== '#/day') location.hash = '#/day';
}

// Any authenticated call that hits 401 clears the session and lands here (client.ts).
setUnauthorizedHandler(() => navigate('#/login'));

window.addEventListener('hashchange', route);
route();
