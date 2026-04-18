export const CSRF_COOKIE = 'ENTLIB_CSRF';
export const CSRF_HEADER = 'X-CSRF-Token';

export function readCsrfToken(): string {
  const entries = document.cookie.split(';');
  for (const raw of entries) {
    const trimmed = raw.trim();
    const eq = trimmed.indexOf('=');
    if (eq === -1) continue;
    const name = trimmed.slice(0, eq);
    if (name === CSRF_COOKIE) {
      return decodeURIComponent(trimmed.slice(eq + 1));
    }
  }
  return '';
}
