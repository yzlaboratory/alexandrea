import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resendVerification, signup, verify } from './authApi';

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
});
