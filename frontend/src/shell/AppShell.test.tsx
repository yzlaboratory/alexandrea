import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AppShell from './AppShell';
import CatalogPlaceholder from './CatalogPlaceholder';
import { SessionProvider } from '../auth/SessionContext';
import * as authApi from '../auth/authApi';

vi.mock('../auth/authApi', () => ({
  logout: vi.fn(),
  fetchSession: vi.fn(),
  switchMediaType: vi.fn(),
}));

const mockedLogout = vi.mocked(authApi.logout);
const mockedFetchSession = vi.mocked(authApi.fetchSession);
const mockedSwitchMediaType = vi.mocked(authApi.switchMediaType);

function renderShell(initialPath: string): void {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <SessionProvider>
        <Routes>
          <Route path="/" element={<div>Landing page</div>} />
          <Route element={<AppShell />}>
            <Route
              path="/:mediaType/:surface"
              element={<CatalogPlaceholder />}
            />
            <Route path="/account" element={<div>Account page</div>} />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe('AppShell', () => {
  beforeEach(() => {
    mockedLogout.mockReset().mockResolvedValue();
    mockedSwitchMediaType.mockReset().mockResolvedValue();
    mockedFetchSession.mockReset().mockResolvedValue({
      email: 'reader@example.com',
      lastMediaType: 'movies',
    });
  });

  it('highlights the media type and surface tabs matching the current route', async () => {
    renderShell('/tv/library');

    expect(
      await screen.findByRole('tab', { name: 'TV', selected: true }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('tab', { name: 'Library', selected: true }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('tab', { name: 'Movies', selected: false }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('tab', { name: 'Watchlist', selected: false }),
    ).toBeInTheDocument();
  });

  it('persists the sticky media type and refreshes the session on a tab switch', async () => {
    renderShell('/movies/watchlist');
    const user = userEvent.setup();
    await screen.findByRole('tab', { name: 'TV' });
    const fetchSessionCallsBeforeSwitch = mockedFetchSession.mock.calls.length;

    await user.click(screen.getByRole('tab', { name: 'TV' }));

    await waitFor(() => {
      expect(mockedSwitchMediaType).toHaveBeenCalledWith('tv');
    });
    await waitFor(() => {
      expect(mockedFetchSession.mock.calls.length).toBeGreaterThan(
        fetchSessionCallsBeforeSwitch,
      );
    });
  });

  it('shows a placeholder body since browsing is not built yet', async () => {
    renderShell('/movies/watchlist');

    expect(await screen.findByText(/nothing to show/i)).toBeInTheDocument();
  });

  it('does not crash and selects no tab for an unrecognized route segment', async () => {
    renderShell('/bogus/whatever');

    expect(await screen.findByText(/nothing to show/i)).toBeInTheDocument();
    for (const tab of screen.getAllByRole('tab')) {
      expect(tab).toHaveAttribute('aria-selected', 'false');
    }
  });

  it('keeps the shell chrome visible on /account', async () => {
    renderShell('/account');

    expect(await screen.findByText('Account page')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /reader@example.com/i }),
    ).toBeInTheDocument();
  });

  it('shows the account menu with settings and log out', async () => {
    renderShell('/movies/watchlist');
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole('button', { name: /reader@example.com/i }),
    );

    expect(
      screen.getByRole('menuitem', { name: /account settings/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: /log out/i }),
    ).toBeInTheDocument();
  });

  it('logs out and returns to the landing page', async () => {
    renderShell('/movies/watchlist');
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole('button', { name: /reader@example.com/i }),
    );
    await user.click(screen.getByRole('menuitem', { name: /log out/i }));

    await waitFor(() => {
      expect(mockedLogout).toHaveBeenCalled();
    });
    expect(await screen.findByText('Landing page')).toBeInTheDocument();
  });
});
