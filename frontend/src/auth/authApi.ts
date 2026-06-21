// Calls the /api/auth endpoints. The backend enables Spring Security's CSRF
// filter (ADR 0021) with a cookie the SPA can read; every state-changing request
// must echo that token back in a header or it is rejected. Reading the cookie and
// setting the header lives here so no caller forgets it.

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

function csrfToken(): string {
  const match = document.cookie
    .split('; ')
    .find((row) => row.startsWith(`${CSRF_COOKIE}=`));
  return match ? decodeURIComponent(match.slice(CSRF_COOKIE.length + 1)) : '';
}

async function postJson(path: string, body: unknown): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [CSRF_HEADER]: csrfToken(),
    },
    // Same-origin behind one CloudFront host (ADR 0014); credentials carry the
    // session and CSRF cookies.
    credentials: 'same-origin',
    body: JSON.stringify(body),
  });
}

/**
 * The reasons a signup submission can fail in a way the form must surface.
 * A duplicate email is deliberately absent: the backend answers signup
 * identically whether the address is new or taken (ADR 0024), so the SPA cannot
 * and must not distinguish it.
 */
export type SignupOutcome =
  | { status: 'accepted' }
  | { status: 'invalid-password' }
  | { status: 'error' };

export async function signup(
  email: string,
  password: string,
): Promise<SignupOutcome> {
  const response = await postJson('/api/auth/signup', { email, password });
  if (response.ok) return { status: 'accepted' };
  // 400 is the server rejecting the password length — the one signal the form
  // may show, since it is about the request, not stored account state.
  if (response.status === 400) return { status: 'invalid-password' };
  return { status: 'error' };
}

export async function resendVerification(email: string): Promise<void> {
  // Always 202 regardless of account state; nothing to branch on.
  await postJson('/api/auth/resend', { email });
}

/** The verify endpoint's three observable outcomes (ADR 0024 collapses the rejections). */
export type VerifyOutcome = 'verified' | 'rejected' | 'error';

export async function verify(token: string): Promise<VerifyOutcome> {
  const response = await postJson('/api/auth/verify', { token });
  if (response.ok) return 'verified';
  // 410 Gone: expired, already used, or unknown — all one "rejected, resend".
  if (response.status === 410) return 'rejected';
  return 'error';
}
