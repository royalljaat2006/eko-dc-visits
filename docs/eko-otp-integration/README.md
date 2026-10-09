# Eko Mobile/OTP Verification — integration guide

Real SMS OTP via Eko's `ekoicici` product (`api.eko.in/ekoicici/v3`). This is
a **live, billed** account — every Send OTP call sends a real SMS at real
cost. There is no sandbox/free mode for this product; test sparingly.

`eko-otp-client.ts` in this folder is a standalone, framework-agnostic
Node/TypeScript client (Node 18+, no dependencies beyond `node:crypto`). It
has no knowledge of Express/Fastify/anything else — wire it into whatever
you're using.

## Credentials

Five values, all required together:

| Value | What it is |
|---|---|
| `EKO_BASE_URL` | `https://api.eko.in/ekoicici/v3` |
| `EKO_DEVELOPER_KEY` | static account identifier — sent as a header on every call |
| `EKO_ACCESS_KEY` | signs every request; the raw value is never sent, only its signature |
| `EKO_INITIATOR_ID` | fixed Merchant id for the account — **not** a phone number |
| `EKO_USER_CODE` | second fixed account credential, required alongside `initiator_id` |

**These are not included here.** They come from Eko's onboarding/Platform
Credentials for whichever Eko account this integration should bill against —
hand them over through your own secure channel (password manager, secrets
vault), not by pasting into a shared doc or chat.

If you're planning to reuse the *same* live account this was originally built
against, ask for those specific values directly rather than assuming a docs
example is usable — Eko's own public docs snippet uses a placeholder
`initiator_id` (`9962981729`) that looks real but belongs to no live account
and will fail with `401 Unauthorized`.

## The four gotchas (all documented inline in the client, repeated here because they're easy to miss)

1. **HMAC signing** — the `secret-key` header is `HMAC-SHA256(timestamp)`,
   keyed by the **base64-encoded access-key string itself**. Do not decode
   that base64 string back to raw bytes before using it as the key — that's
   the natural-looking reading of Eko's public docs snippet, and it's wrong
   for this account. It fails as a bare `401` with no useful error body, so
   it's easy to misdiagnose as a credentials problem.

2. **`initiator_id` / `user_code` are fixed account credentials**, never a
   phone number and never copied from a docs example. Wrong values here
   fail with a specific, readable error: `"Invalid Sender/Initiator"` with
   `invalid_params.initiator_id: "Merchant does not exist in system."`

3. **`client_ref_id` must match between Send and Verify** for one login
   attempt. Eko correlates the pending OTP by this ref, not just the phone
   number. A fresh random ref on Verify — even with the objectively correct
   OTP code — fails with `"OTP not found. Please make the OTP request
   again."` Your backend needs to remember the ref returned by `sendOtp()`
   (keyed by phone number) and pass that exact value into `verifyOtp()`.

4. **The OTP is single-use.** If your app logic can end up calling
   `verifyOtp()` a second time for the same phone+otp pair — for example, a
   first Verify succeeds cryptographically, but your own backend then
   rejects the overall request for an unrelated reason (a new-user flow
   needing more info, say), and the client retries Verify with the same
   code — that second call to Eko fails with `"OTP not found"` even though
   the code is correct, because Eko already consumed it on the first call.
   Cache "this phone+otp already verified successfully" for a few minutes
   and skip re-asking Eko if a retry reuses the same pair.

## Usage

```ts
import { loadEkoConfigFromEnv, sendOtp, verifyOtp } from "./eko-otp-client";

const eko = loadEkoConfigFromEnv();
if (!eko) throw new Error("EKO_* env vars not set");

// --- your "send OTP" endpoint ---
const sent = await sendOtp(eko, "9800000001");
if (!sent.ok) {
  // sent.reason is Eko's actual message — log it, don't swallow it
  throw new Error(sent.reason);
}
// Persist sent.clientRefId keyed by phone number (DB, Redis, or even an
// in-process Map with a short TTL if you're a single instance) — you need
// it again for verifyOtp() below.

// --- your "verify OTP" endpoint, later, same phone number ---
const clientRefId = /* looked up from wherever you stored it */;
const verified = await verifyOtp(eko, "9800000001", otpFromUser, clientRefId);
if (!verified.ok) {
  throw new Error(verified.reason);
}
// OTP confirmed — proceed with session/login logic.
```

## Where this came from

Built and debugged for the `eko-dc-visits` project (`backend/src/auth/eko.ts`
+ `backend/src/server.ts`). All four gotchas above were found by reading
Eko's actual raw HTTP response for each failure — not guessed — so trust the
error messages over intuition if something still doesn't work; they're
specific and Eko's API returns them directly (`message` /
`invalid_params` fields in the JSON body).
