/**
 * LOGIN screen: phone → OTP request → OTP verify (C2 /auth/otp/*).
 * Dev gateway stub always sends OTP 000000 (C2 requestOtp summary).
 */
import { ApiError, requestOtp, verifyOtp } from '../api/client.ts';
import ekoLogo from '../assets/eko-logo.jpeg';

export function renderLogin(root: HTMLElement, onLoggedIn: () => void): () => void {
  root.innerHTML = `
    <main class="login">
      <div class="login-card">
        <img class="login-logo" src="${ekoLogo}" alt="Eko" />
        <p class="login-sub">DC Visit Management</p>
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
          <p class="hint">Dev environments: the OTP is always <code>000000</code>.</p>
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
    try {
      await verifyOtp(phoneEl.value.trim(), otpEl.value.trim());
      onLoggedIn();
    } catch (err) {
      showError(err);
    }
  });

  return () => {
    /* nothing to tear down */
  };
}
