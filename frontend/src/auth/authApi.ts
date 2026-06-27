// Every state-changing request must echo the readable CSRF cookie back as a
// header, or the backend rejects it. Centralised here so no caller forgets.

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
    // credentials carry the session and CSRF cookies back to the API.
    credentials: 'same-origin',
    body: JSON.stringify(body),
  });
}

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
  // A generic validation 400 (e.g. a malformed email) lacks the marker, so it
  // falls through to the generic error rather than being mislabelled a password
  // problem.
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

export type VerifyOutcome = 'verified' | 'rejected' | 'error';

export async function verify(token: string): Promise<VerifyOutcome> {
  const response = await postJson('/api/auth/verify', { token });
  if (response.ok) return 'verified';
  // 410 Gone: expired, already used, or unknown — all one "rejected, resend".
  if (response.status === 410) return 'rejected';
  return 'error';
}
