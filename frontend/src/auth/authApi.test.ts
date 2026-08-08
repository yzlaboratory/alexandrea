import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchSession,
  login,
  logout,
  resendVerification,
  signup,
  switchMediaType,
  verify,
} from './authApi';

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

describe('authApi', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=tok-123';
    fetchMock = vi.fn().mockResolvedValue(response(202));
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('echoes the CSRF cookie back in the X-XSRF-TOKEN header', async () => {
    await signup('reader@example.com', 'a-good-long-password');

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/auth/signup');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe(
      'tok-123',
    );
    expect(init.body).toBe(
      JSON.stringify({
        email: 'reader@example.com',
        password: 'a-good-long-password',
      }),
    );
  });

  it('maps signup responses to outcomes', async () => {
    fetchMock.mockResolvedValueOnce(response(202));
    expect(await signup('a@example.com', 'pw')).toEqual({ status: 'accepted' });

    fetchMock.mockResolvedValueOnce(
      response(400, { type: 'urn:alexandrea:auth:password-policy' }),
    );
    expect(await signup('a@example.com', 'pw')).toEqual({
      status: 'invalid-password',
    });

    fetchMock.mockResolvedValueOnce(response(500));
    expect(await signup('a@example.com', 'pw')).toEqual({ status: 'error' });
  });

  it('does not mislabel a generic validation 400 as a password problem', async () => {
    // A malformed-email 400 carries no password-policy marker, so the form must
    // fall back to the generic error rather than blaming the password length.
    fetchMock.mockResolvedValueOnce(response(400, { type: 'about:blank' }));
    expect(await signup('bad-email', 'a-good-long-password')).toEqual({
      status: 'error',
    });
  });

  it('maps verify responses to outcomes, collapsing 410 to one rejection', async () => {
    fetchMock.mockResolvedValueOnce(response(200));
    expect(await verify('good')).toBe('verified');

    fetchMock.mockResolvedValueOnce(response(410));
    expect(await verify('stale')).toBe('rejected');

    fetchMock.mockResolvedValueOnce(response(500));
    expect(await verify('boom')).toBe('error');
  });

  it('posts the address to the resend endpoint', async () => {
    await resendVerification('reader@example.com');

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/auth/resend');
    expect(init.body).toBe(JSON.stringify({ email: 'reader@example.com' }));
  });

  it('maps a verified login to authenticated, carrying the sticky media type', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { verified: true, lastMediaType: 'games' }),
    );
    expect(await login('reader@example.com', 'pw')).toEqual({
      status: 'authenticated',
      lastMediaType: 'games',
    });
  });

  it('defaults lastMediaType to null when the account has never chosen one', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { verified: true, lastMediaType: null }),
    );
    expect(await login('reader@example.com', 'pw')).toEqual({
      status: 'authenticated',
      lastMediaType: null,
    });
  });

  it('maps correct-password-but-unverified to the unverified outcome', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { verified: false, lastMediaType: null }),
    );
    expect(await login('reader@example.com', 'pw')).toEqual({
      status: 'unverified',
    });
  });

  it('maps a 401 to invalid-credentials regardless of body', async () => {
    fetchMock.mockResolvedValueOnce(response(401));
    expect(await login('nobody@example.com', 'pw')).toEqual({
      status: 'invalid-credentials',
    });
  });

  it('maps an unexpected server error to the generic error outcome', async () => {
    fetchMock.mockResolvedValueOnce(response(500));
    expect(await login('reader@example.com', 'pw')).toEqual({
      status: 'error',
    });
  });

  it('posts to the logout endpoint', async () => {
    fetchMock.mockResolvedValueOnce(response(204));
    await logout();

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/auth/logout');
    expect(init.method).toBe('POST');
  });

  it('posts the chosen type to the media-type endpoint', async () => {
    fetchMock.mockResolvedValueOnce(response(204));
    await switchMediaType('tv');

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/auth/media-type');
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ mediaType: 'tv' }));
  });

  it('fetches the current session when authenticated', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { email: 'reader@example.com', lastMediaType: 'books' }),
    );
    expect(await fetchSession()).toEqual({
      email: 'reader@example.com',
      lastMediaType: 'books',
    });

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/auth/session');
    expect(init.headers).toBeUndefined();
  });

  it('resolves to null when there is no session', async () => {
    fetchMock.mockResolvedValueOnce(response(401));
    expect(await fetchSession()).toBeNull();
  });
});
