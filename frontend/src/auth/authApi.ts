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

export type LoginOutcome =
  | { status: 'authenticated'; lastMediaType: string | null }
  | { status: 'unverified' }
  | { status: 'invalid-credentials' }
  | { status: 'error' };

export async function login(
  email: string,
  password: string,
): Promise<LoginOutcome> {
  const response = await postJson('/api/auth/login', { email, password });
  // 401: the backend's generic rejection, deliberately identical whether the
  // email is unknown or the password is wrong (ADR 0024).
  if (response.status === 401) return { status: 'invalid-credentials' };
  if (!response.ok) return { status: 'error' };

  const body = (await response.json().catch(() => null)) as {
    verified?: boolean;
    lastMediaType?: string | null;
  } | null;
  if (body?.verified === true) {
    return {
      status: 'authenticated',
      lastMediaType: body.lastMediaType ?? null,
    };
  }
  if (body?.verified === false) return { status: 'unverified' };
  return { status: 'error' };
}

export async function logout(): Promise<void> {
  await postJson('/api/auth/logout', {});
}

// The response body is generic regardless of account state (ADR 0024), so
// there is nothing for the caller to branch on beyond success vs. failure,
// which the caller catches.
export async function requestPasswordReset(email: string): Promise<void> {
  const response = await postJson('/api/auth/forgot-password', { email });
  if (!response.ok) throw new Error('Failed to request password reset');
}

export type ResetPasswordOutcome =
  | { status: 'reset' }
  | { status: 'rejected' }
  | { status: 'invalid-password' }
  | { status: 'error' };

export async function resetPassword(
  token: string,
  newPassword: string,
): Promise<ResetPasswordOutcome> {
  const response = await postJson('/api/auth/reset-password', {
    token,
    newPassword,
  });
  if (response.ok) return { status: 'reset' };
  // 410 Gone: expired, already used, or unknown — all one "rejected" link.
  if (response.status === 410) return { status: 'rejected' };
  if (await isPasswordPolicyRejection(response)) {
    return { status: 'invalid-password' };
  }
  return { status: 'error' };
}

export type ChangePasswordOutcome =
  | { status: 'changed' }
  | { status: 'incorrect-current-password' }
  | { status: 'invalid-password' }
  | { status: 'error' };

export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<ChangePasswordOutcome> {
  const response = await postJson('/api/auth/change-password', {
    currentPassword,
    newPassword,
  });
  if (response.ok) return { status: 'changed' };
  if (response.status === 401) return { status: 'incorrect-current-password' };
  if (await isPasswordPolicyRejection(response)) {
    return { status: 'invalid-password' };
  }
  return { status: 'error' };
}

export type RequestEmailChangeOutcome =
  | { status: 'requested' }
  | { status: 'incorrect-current-password' }
  | { status: 'error' };

// The response is generic regardless of whether the new address is already
// registered (ADR 0024), so there is nothing to branch on beyond the
// authenticated-action failure mode (wrong current password) vs. success.
export async function requestEmailChange(
  currentPassword: string,
  newEmail: string,
): Promise<RequestEmailChangeOutcome> {
  const response = await postJson('/api/auth/change-email', {
    currentPassword,
    newEmail,
  });
  if (response.ok) return { status: 'requested' };
  if (response.status === 401) return { status: 'incorrect-current-password' };
  return { status: 'error' };
}

export type ConfirmEmailChangeOutcome = 'changed' | 'rejected' | 'error';

export async function confirmEmailChange(
  token: string,
): Promise<ConfirmEmailChangeOutcome> {
  const response = await postJson('/api/auth/confirm-email-change', { token });
  if (response.ok) return 'changed';
  // 410 Gone: expired, already used, or unknown — all one "rejected" link.
  if (response.status === 410) return 'rejected';
  return 'error';
}

export async function switchMediaType(mediaType: string): Promise<void> {
  await postJson('/api/auth/media-type', { mediaType });
}

export interface SessionUser {
  email: string;
  lastMediaType: string | null;
}

// GET is CSRF-exempt (Spring only guards state-changing methods), so this
// skips the token header the other calls need.
export async function fetchSession(): Promise<SessionUser | null> {
  const response = await fetch('/api/auth/session', {
    credentials: 'same-origin',
  });
  if (!response.ok) return null;
  return (await response.json()) as SessionUser;
}
