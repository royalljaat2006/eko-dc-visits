/**
 * LOGIN screen: phone → OTP request → OTP verify (C2 /auth/otp/*).
 * OTP is delivered by the Eko SMS gateway (C2 requestOtp).
 * An unregistered number gets 422 "Name Required" from verify: we reveal a
 * name field and resend verify with `name` (backend self-registers a DC and
 * remembers the already-proven OTP for a few minutes, so the same OTP works).
 */
import { ApiError, requestOtp, verifyOtp } from '../api/client.ts';
import { isNameRequired, normaliseName } from '../lib/auth.ts';
import ekoLogo from '../assets/eko-logo.jpeg';

export function renderLogin(root: HTMLElement, onLoggedIn: () => void): () => void {
  root.innerHTML = `
    <main class="login">
      <div class="login-card">
        <img class="login-logo" src="${ekoLogo}" alt="Eko" />
        <p class="login-sub">DC Visit App</p>
        <form id="login-form">
          <label>
            Phone
            <input id="phone" name="phone" type="tel" inputmode="numeric"
                   pattern="[6-9][0-9]{9}" maxlength="10" placeholder="10-digit mobile" required />
          </label>
          <button id="send-otp" type="button">Send OTP</button>
          <label>
            OTP
            <input id="otp" name="otp" type="text" inputmode="numeric" maxlength="6"
                   placeholder="6-digit OTP" autocomplete="one-time-code" required />
          </label>
          <div id="name-block" hidden>
            <label>
              Your full name
              <input id="name" name="name" type="text" autocomplete="name"
                     minlength="2" maxlength="120" placeholder="e.g. Asha Devi" />
            </label>
            <p class="hint">This number isn't registered yet. Signing in will create a new DC account under this name.</p>
          </div>
          <p class="hint">Only registered Eko numbers can sign in. An OTP is sent to your mobile.</p>
          <button id="verify-otp" type="submit" class="btn-primary">Verify &amp; sign in</button>
          <p id="login-status" class="status" role="status"></p>
          <p id="login-error" class="error" role="alert"></p>
        </form>
      </div>
    </main>
  `;

  const phoneEl = root.querySelector<HTMLInputElement>('#phone')!;
  const otpEl = root.querySelector<HTMLInputElement>('#otp')!;
  const sendBtn = root.querySelector<HTMLButtonElement>('#send-otp')!;
  const nameBlock = root.querySelector<HTMLDivElement>('#name-block')!;
  const nameEl = root.querySelector<HTMLInputElement>('#name')!;
  const verifyBtn = root.querySelector<HTMLButtonElement>('#verify-otp')!;
  const form = root.querySelector<HTMLFormElement>('#login-form')!;
  const statusEl = root.querySelector<HTMLParagraphElement>('#login-status')!;
  const errorEl = root.querySelector<HTMLParagraphElement>('#login-error')!;

  function showError(err: unknown): void {
    statusEl.textContent = '';
    if (err instanceof ApiError) {
      const p = err.problem;
      errorEl.textContent = p.detail ? `${p.title}: ${p.detail}` : `${p.title} (HTTP ${p.status})`;
    } else if (err instanceof Error) {
      errorEl.textContent = err.message;
    } else {
      errorEl.textContent = 'Unexpected error';
    }
  }

  function setNameRequired(on: boolean): void {
    nameBlock.hidden = !on;
    nameEl.required = on;
    verifyBtn.innerHTML = on ? 'Create account &amp; sign in' : 'Verify &amp; sign in';
  }

  // A different phone number may already be registered — hide the name step again.
  phoneEl.addEventListener('input', () => setNameRequired(false));

  sendBtn.addEventListener('click', async () => {
    errorEl.textContent = '';
    statusEl.textContent = '';
    if (!phoneEl.reportValidity()) return;
    sendBtn.disabled = true;
    try {
      await requestOtp(phoneEl.value.trim());
      statusEl.textContent = 'OTP sent. Enter it below.';
    } catch (err) {
      showError(err);
    } finally {
      sendBtn.disabled = false;
    }
  });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errorEl.textContent = '';
    statusEl.textContent = '';
    let name: string | undefined;
    if (!nameBlock.hidden) {
      const n = normaliseName(nameEl.value);
      if (n === null) {
        errorEl.textContent = 'Please enter your full name (at least 2 characters).';
        nameEl.focus();
        return;
      }
      name = n;
    }
    verifyBtn.disabled = true;
    try {
      await verifyOtp(phoneEl.value.trim(), otpEl.value.trim(), name);
      onLoggedIn();
    } catch (err) {
      if (err instanceof ApiError && isNameRequired(err.problem) && nameBlock.hidden) {
        setNameRequired(true);
        statusEl.textContent = 'New number — enter your full name to create your DC account.';
        nameEl.focus();
      } else {
        showError(err);
      }
    } finally {
      verifyBtn.disabled = false;
    }
  });

  return () => {
    /* nothing to tear down */
  };
}
