import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import App from './App';

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

function renderAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe('App routing', () => {
  // App owns SessionProvider internally (LoginPage needs it outside
  // RequireAuth too), so every render here triggers a real session lookup —
  // stubbed anonymous by default, which is the right starting state for all
  // of these routes.
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(401)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the landing pitch, a Sign up CTA, and a Log in link at /', () => {
    renderAt('/');

    expect(
      screen.getByRole('heading', { level: 1, name: /alexandrea/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /sign up/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /log in/i })).toBeInTheDocument();
  });

  it('shows no catalog or user data surfaces on the landing page', () => {
    renderAt('/');

    // The pitch may use the words "watchlist"/"library", but there must be no
    // actual user-data surface: no rendered lists/grids of items and no
    // session affordances (logout) reachable without auth.
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByRole('grid')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /log ?out|sign ?out/i }),
    ).not.toBeInTheDocument();
  });

  it('renders the signup form at /signup', () => {
    renderAt('/signup');

    expect(
      screen.getByRole('heading', { name: /create your account/i }),
    ).toBeInTheDocument();
  });

  it('falls back to the landing page for unknown paths', () => {
    renderAt('/nope');

    expect(
      screen.getByRole('heading', { level: 1, name: /alexandrea/i }),
    ).toBeInTheDocument();
  });

  it('renders the login form at /login', () => {
    renderAt('/login');

    expect(
      screen.getByRole('heading', { name: /log in/i }),
    ).toBeInTheDocument();
  });

  it('redirects an anonymous visitor to /login instead of the app shell', async () => {
    renderAt('/movies/watchlist');

    expect(
      await screen.findByRole('heading', { name: /log in/i }),
    ).toBeInTheDocument();
  });
});
