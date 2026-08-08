import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import LoginPage from './LoginPage';
import { SessionProvider } from './SessionContext';
import * as authApi from './authApi';

vi.mock('./authApi', () => ({
  login: vi.fn(),
  resendVerification: vi.fn(),
  fetchSession: vi.fn(),
}));

const mockedLogin = vi.mocked(authApi.login);
const mockedResend = vi.mocked(authApi.resendVerification);
const mockedFetchSession = vi.mocked(authApi.fetchSession);

function renderLoginPage(
  initialEntry: string | { pathname: string; state?: unknown },
): void {
  render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <SessionProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/movies/watchlist"
            element={<div>Movies watchlist</div>}
          />
          <Route path="/books/watchlist" element={<div>Books watchlist</div>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

async function fillAndSubmit(email: string, password: string): Promise<void> {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/email/i), email);
  await user.type(screen.getByLabelText(/password/i), password);
  await user.click(screen.getByRole('button', { name: /log in/i }));
}

describe('LoginPage', () => {
  beforeEach(() => {
    mockedLogin.mockReset();
    mockedResend.mockReset();
    mockedFetchSession.mockReset().mockResolvedValue(null);
  });

  it('lands on the sticky media type after a successful login', async () => {
    mockedLogin.mockResolvedValue({
      status: 'authenticated',
      lastMediaType: 'books',
    });
    renderLoginPage('/login');

    await fillAndSubmit('reader@example.com', 'a-good-long-password');

    expect(await screen.findByText('Books watchlist')).toBeInTheDocument();
  });

  it('defaults to Movies when the account has never chosen a media type', async () => {
    mockedLogin.mockResolvedValue({
      status: 'authenticated',
      lastMediaType: null,
    });
    renderLoginPage('/login');

    await fillAndSubmit('reader@example.com', 'a-good-long-password');

    expect(await screen.findByText('Movies watchlist')).toBeInTheDocument();
  });

  it('returns to the originally requested URL after logging in', async () => {
    mockedLogin.mockResolvedValue({
      status: 'authenticated',
      lastMediaType: 'movies',
    });
    renderLoginPage({
      pathname: '/login',
      state: { from: '/books/watchlist' },
    });

    await fillAndSubmit('reader@example.com', 'a-good-long-password');

    expect(await screen.findByText('Books watchlist')).toBeInTheDocument();
  });

  it('shows a resend panel when the password is correct but the account is unverified', async () => {
    mockedLogin.mockResolvedValue({ status: 'unverified' });
    renderLoginPage('/login');

    await fillAndSubmit('pending@example.com', 'a-good-long-password');

    expect(
      await screen.findByRole('heading', { name: /check your email/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/pending@example.com/i)).toBeInTheDocument();
  });

  it('shows the generic rejection for invalid credentials', async () => {
    mockedLogin.mockResolvedValue({ status: 'invalid-credentials' });
    renderLoginPage('/login');

    await fillAndSubmit('nobody@example.com', 'wrong-password');

    expect(
      await screen.findByText(/email or password is incorrect/i),
    ).toBeInTheDocument();
  });

  it('links to the forgot-password page', () => {
    renderLoginPage('/login');

    expect(
      screen.getByRole('link', { name: /forgot your password/i }),
    ).toHaveAttribute('href', '/forgot-password');
  });
});
