import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionProvider } from './SessionContext';
import RequireAuth from './RequireAuth';

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

function renderProtectedApp(initialPath: string): void {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <SessionProvider>
        <Routes>
          <Route path="/login" element={<div>Login page</div>} />
          <Route element={<RequireAuth />}>
            <Route
              path="/movies/watchlist"
              element={<div>Protected content</div>}
            />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('RequireAuth', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows a loading indicator while the session is being resolved', () => {
    fetchMock.mockReturnValue(Promise.race([]));
    renderProtectedApp('/movies/watchlist');

    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('redirects an anonymous visitor to /login instead of the protected content', async () => {
    fetchMock.mockResolvedValueOnce(response(401));
    renderProtectedApp('/movies/watchlist');

    await waitFor(() => {
      expect(screen.getByText('Login page')).toBeInTheDocument();
    });
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders the protected content for an authenticated visitor', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { email: 'reader@example.com', lastMediaType: 'movies' }),
    );
    renderProtectedApp('/movies/watchlist');

    await waitFor(() => {
      expect(screen.getByText('Protected content')).toBeInTheDocument();
    });
  });
});
