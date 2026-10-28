/**
 * Pure auth-flow helpers (no I/O) — kept out of api/client.ts so they can be
 * unit-tested under node:test without a DOM or fetch.
 */

/**
 * POST /auth/otp/verify answers 422 "Name Required" when the phone is not yet
 * registered; the client must resend verify with a `name` to self-register a DC.
 */
export function isNameRequired(problem: { status: number; title: string }): boolean {
  return problem.status === 422 && problem.title.trim().toLowerCase() === 'name required';
}

/** Backend accepts `name` once trimmed to ≥ 2 characters (max 120 kept). */
export function normaliseName(raw: string): string | null {
  const name = raw.trim().replace(/\s+/g, ' ');
  return name.length >= 2 ? name.slice(0, 120) : null;
}

/**
 * Wraps an async fn so concurrent callers share ONE in-flight promise. Used for
 * token refresh: refresh tokens are single-use, so two parallel 401s must not
 * each spend the token. Once the promise settles the next call starts afresh.
 */
export function singleFlight<T>(fn: () => Promise<T>): () => Promise<T> {
  let inFlight: Promise<T> | null = null;
  return () => {
    if (inFlight === null) {
      inFlight = (async () => {
        try {
          return await fn();
        } finally {
          inFlight = null;
        }
      })();
    }
    return inFlight;
  };
}
