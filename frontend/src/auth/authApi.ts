// The backend enables Spring Security's CSRF filter (ADR 0021) with a cookie the
// SPA can read; every state-changing request must echo that token in a header or
// it is rejected. Centralised here so no caller forgets it.

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

// Stable discriminator the backend stamps on the password-policy 400 (matches
// AuthExceptionHandler.PASSWORD_POLICY_PROBLEM_TYPE).
const PASSWORD_POLICY_PROBLEM_TYPE = 'urn:alexandrea:auth:password-policy';

export async function signup(
  email: string,
  password: string,
): Promise<SignupOutcome> {
  const response = await postJson('/api/auth/signup', { email, password });
  if (response.ok) return { status: 'accepted' };
  // Only the password-policy 400 may be shown — it is about the request, not
  // stored account state. A generic validation 400 (e.g. a malformed email)
  // lacks the marker and falls through to the generic error rather than being
  // mislabelled a password-length problem.
  if (await isPasswordPolicyRejection(response)) {
    return { status: 'invalid-password' };
  }
  return { status: 'error' };
}

async function isPasswordPolicyRejection(response: Response): Promise<boolean> {
  if (response.status !== 400) return false;
  const problem = (await response.json().catch(() => null)) as {
    type?: string;
  } | null;
  return problem?.type === PASSWORD_POLICY_PROBLEM_TYPE;
}

export async function resendVerification(email: string): Promise<void> {
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
