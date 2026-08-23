import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
  // of these routes. Individual tests can queue further mockResolvedValueOnce
  // responses on top of this default for subsequent requests.
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue(response(401));
    vi.stubGlobal('fetch', fetchMock);
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

  it('returns an anonymous visitor to their originally requested URL after logging in', async () => {
    document.cookie = 'XSRF-TOKEN=test-token';
    renderAt('/books/library');

    // RequireAuth's own redirect — not a fabricated location.state — is what
    // carries the destination through to here.
    await screen.findByRole('heading', { name: /log in/i });

    fetchMock.mockResolvedValueOnce(
      response(200, { verified: true, lastMediaType: 'movies' }),
    );
    fetchMock.mockResolvedValueOnce(
      response(200, { email: 'reader@example.com', lastMediaType: 'movies' }),
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), 'reader@example.com');
    await user.type(screen.getByLabelText(/password/i), 'a-good-long-password');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    expect(
      await screen.findByRole('tab', { name: 'Books', selected: true }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('tab', { name: 'Library', selected: true }),
    ).toBeInTheDocument();

    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('reaches the real CatalogPage when the Catalog nav tab is clicked', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { email: 'reader@example.com', lastMediaType: 'movies' }),
    );
    renderAt('/movies/watchlist');
    const user = userEvent.setup();
    await screen.findByText('Nothing to show here yet.');

    await user.click(screen.getByRole('tab', { name: 'Catalog' }));

    // Distinguishing content only the real CatalogPage renders — not just
    // "some route matched" (CatalogPlaceholder renders neither) — proving
    // /:mediaType/catalog's higher route specificity actually beats
    // /:mediaType/:surface for this click, the same precedence App.tsx's
    // route comment documents.
    expect(
      await screen.findByRole('heading', { level: 1, name: /movies catalog/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('textbox', { name: /search movies/i }),
    ).toBeInTheDocument();
  });
});
